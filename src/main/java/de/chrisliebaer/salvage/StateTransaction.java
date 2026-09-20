package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import de.chrisliebaer.salvage.entity.SalvageContainer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.Map;

/**
 * This class is responsible for changing and maintaining the state of containers during backups. It implements AutoCloseable to be able to roll back the state of
 * containers in all cases.
 */
@Slf4j
@AllArgsConstructor
public class StateTransaction implements AutoCloseable {
	
	/**
	 * Number of times to try waiting on container to reach stable state.
	 */
	private static final int RETRY_COUNT = 3;
	
	/**
	 * Number of milliseconds to wait between retries.
	 */
	private static final int RETRY_DELAY = 5000;
	
	private final DockerClient docker;
	private final Map<SalvageContainer, AffectedContainer> affectedContainers = new IdentityHashMap<>();
	
	@Override
	public void close() {
		if (affectedContainers.isEmpty())
			return;
		
		boolean interrupted = Thread.interrupted();
		var failure = new IllegalStateException("failed to restore one or more containers after backup");
		try {
			for (var container : new ArrayList<>(affectedContainers.keySet())) {
				try {
					restore(container);
				} catch (Throwable e) {
					failure.addSuppressed(new IllegalStateException("failed to restore " + container.name(), e));
				} finally {
					interrupted |= Thread.interrupted();
				}
			}
		} finally {
			if (interrupted) Thread.currentThread().interrupt();
		}
		if (failure.getSuppressed().length != 0) throw failure;
	}
	
	public void prepare(SalvageContainer container) throws InterruptedException {
		var remainingRetries = RETRY_COUNT;
		
		InspectContainerResponse inspect;
		do {
			// if container is restarting, give it some time to finish
			inspect = docker.inspectContainerCmd(container.id()).exec();
			var state = inspect.getState();
			if (state.getRestarting()) {
				log.debug("container {} is restarting, waiting {}ms ({} tries remaining)", container.name(), RETRY_DELAY, remainingRetries);
				Thread.sleep(RETRY_DELAY);
			} else {
				break;
			}
			
		} while (remainingRetries-- > 0 && (inspect.getState().getRestarting()));
		var state = inspect.getState();
		
		// abort, rather than perform backup with container in unknown state
		if (state.getRestarting()) {
			throw new IllegalStateException("container '" + container.name() + "' has not reached stable state after " + RETRY_COUNT + " retries");
		}
		
		// run preperation command if container has one and is running (not paused)
		boolean preCommandRun = false;
		if (container.commandPre().isPresent() && state.getRunning() && !state.getPaused()) {
			var command = container.commandPre().get();
			log.debug("running preperation command '{}' on container {}", command, container.name());
			long exitCode;
			try {
				exitCode = command.run(docker, container);
			} catch (Throwable e) {
				throw new IllegalStateException("preperation command '" + command + "' failed on container '" + container.name() + "'", e);
			}
			
			if (container.exitCodeBehaviour().check(exitCode)) {
				log.debug("preperation command '{}' on container {} exited with code {}", command, container.name(), exitCode);
			} else {
				throw new IllegalStateException("preperation command '" + command + "' on container '" + container.name() + "' exited with code " + exitCode);
			}
			
			preCommandRun = true;
		}
		
		
		var affected = new AffectedContainer((d, c) -> {}, preCommandRun);
		// Track before the Docker mutation: the request may succeed even if its response fails.
		affectedContainers.put(container, affected);
		
		// alter container state, if necessary
		switch (container.action()) {
			case IGNORE -> log.debug("container {} has no action, skipping", container.name());
			case STOP -> {
				// container must be running and not paused, if it's not running at all, there is no need to stop it (but we must not start it again)
				if (state.getRunning()) {
					if (state.getPaused()) {
						throw new IllegalStateException("container '" + container.name() + "' is paused, cannot stop");
					}
					log.debug("stopping container {}", container.name());
					affected.restoreFn = (d, c) -> {
						log.debug("starting container {}", c.name());
						if (!d.inspectContainerCmd(c.id()).exec().getState().getRunning())
							d.startContainerCmd(c.id()).exec();
					};
					docker.stopContainerCmd(container.id()).exec();
				}
			}
			case PAUSE -> {
				// if container is running, we need to pause it (otherwise we don't need to do anything)
				if (state.getRunning() && !state.getPaused()) {
					log.debug("pausing container {}", container.name());
					affected.restoreFn = (d, c) -> {
						log.debug("unpausing container {}", c.name());
						if (d.inspectContainerCmd(c.id()).exec().getState().getPaused())
							d.unpauseContainerCmd(c.id()).exec();
					};
					docker.pauseContainerCmd(container.id()).exec();
				}
			}
		}
		

	}
	
	public void restore(SalvageContainer container) throws Throwable {
		var affected = affectedContainers.get(container);
		if (affected == null) return;
		if (!affected.stateRestored) {
			affected.restoreFn.run(docker, container);
			affected.stateRestored = true;
		}
		
		if (affected.preCommandRun && container.commandPost().isPresent()) {
			var command = container.commandPost().get();
			log.debug("running post command '{}' on container {}", command, container.name());
			var exitCode = command.run(docker, container);
			if (container.exitCodeBehaviour().check(exitCode)) {
				log.debug("post command '{}' on container {} exited with code {}", command, container.name(), exitCode);
			} else {
				throw new IllegalStateException("post command '" + command + "' on container '" + container.name() + "' exited with code " + exitCode);
				
			}
		}
		affectedContainers.remove(container);
	}
	
	/**
	 * This class is used to store the dynamic restore function for a container, depending on the action that was performed and which state it was in before.
	 *
			 */
	private static final class AffectedContainer {
		private RestoreFunction restoreFn;
		private final boolean preCommandRun;
		private boolean stateRestored;
		private AffectedContainer(RestoreFunction restoreFn, boolean preCommandRun) {
			this.restoreFn = restoreFn;
			this.preCommandRun = preCommandRun;
		}
	}
	
	/**
	 * This interface is responsible for restoring the state of a container after a backup.
	 */
	private interface RestoreFunction {
		
		void run(DockerClient docker, SalvageContainer container) throws Throwable;
	}
}
