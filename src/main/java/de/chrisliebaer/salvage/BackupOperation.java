package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import de.chrisliebaer.salvage.entity.BackupMeta;
import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import de.chrisliebaer.salvage.reporting.TideLog;
import de.chrisliebaer.salvage.reporting.VolumeLog;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.ThreadContext;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
public class BackupOperation implements AutoCloseable {
	private final DockerClient docker;
	private final ExecutorService executor;
	private final Map<SalvageCrane, Semaphore> cranes = new IdentityHashMap<>();
	private final BackupMeta.HostMeta hostMeta;
	private final TideLog tideLog;

	public BackupOperation(DockerClient docker, int maxConcurrent, Collection<SalvageCrane> cranes,
						   BackupMeta.HostMeta hostMeta, TideLog tideLog) {
		this.docker = docker;
		this.hostMeta = hostMeta;
		this.tideLog = tideLog;
		executor = Executors.newFixedThreadPool(maxConcurrent, Thread.ofPlatform().name("CraneShip", 0).factory());
		for (var crane : cranes) this.cranes.put(crane, new Semaphore(crane.maxConcurrent()));
	}

	@Override
	public void close() {
		executor.shutdown();
		boolean interrupted = Thread.interrupted();
		for (;;) {
			try {
				if (executor.awaitTermination(1, TimeUnit.DAYS)) break;
			} catch (InterruptedException e) {
				interrupted = true;
				executor.shutdownNow();
			}
		}
		if (interrupted) Thread.currentThread().interrupt();
	}

	public void backupVolumes(SalvageCrane crane, Collection<SalvageVolume> volumes) throws InterruptedException {
		var completed = new ExecutorCompletionService<Void>(executor);
		// Register on the coordinator, including work that may be cancelled before it starts.
		for (var volume : volumes) {
			var volumeLog = tideLog.getVolumeLog(volume, crane);
			completed.submit(() -> {
				var semaphore = cranes.get(crane);
				boolean acquired = false;
				try {
					semaphore.acquire();
					acquired = true;
					ThreadContext.put("volume", volume.name());
					backupVolume(volume, crane, volumeLog);
					return null;
				} finally {
					if (acquired) semaphore.release();
					ThreadContext.remove("volume");
				}
			});
		}
		try {
			for (int i = 0; i < volumes.size(); i++) completed.take().get();
		} catch (InterruptedException e) {
			executor.shutdownNow();
			// Future cancellation is not evidence that Docker cleanup has finished.
			// Source applications must stay quiesced until every worker really exits.
			while (!executor.isTerminated()) {
				try { executor.awaitTermination(1, TimeUnit.DAYS); }
				catch (InterruptedException again) { e.addSuppressed(again); }
			}
			throw e;
		} catch (ExecutionException e) {
			executor.shutdownNow();
			boolean interrupted = false;
			while (!executor.isTerminated()) {
				try { executor.awaitTermination(1, TimeUnit.DAYS); }
				catch (InterruptedException again) { interrupted = true; e.addSuppressed(again); }
			}
			if (interrupted) Thread.currentThread().interrupt();
			throw new IllegalStateException("backup worker failed", e.getCause());
		}
	}

	private void backupVolume(SalvageVolume volume, SalvageCrane crane, VolumeLog volumeLog) {
		try {
			volumeLog.start();
			new SalvageVessel(docker, volume, crane, hostMeta, volumeLog).start();
			volumeLog.success();
		} catch (Throwable e) {
			log.error("error while backing up volume '{}'", volume.name(), e);
			volumeLog.failure(e);
		}
	}
}
