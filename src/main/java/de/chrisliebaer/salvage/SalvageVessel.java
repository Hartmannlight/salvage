package de.chrisliebaer.salvage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.SELContext;
import com.github.dockerjava.api.model.Volume;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.chrisliebaer.salvage.entity.BackupMeta;
import de.chrisliebaer.salvage.entity.FrameCallback;
import de.chrisliebaer.salvage.entity.SalvageCrane;
import de.chrisliebaer.salvage.entity.SalvageVolume;
import de.chrisliebaer.salvage.reporting.VolumeLog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class SalvageVessel {
	
	// in seconds!
	private static final int BACKUP_SHUTDOWN_GRACE_TIMEOUT = 120;
	
	private static final int ROOT_UID = 0;
	private static final int ROOT_GID = 0;
	
	@SuppressWarnings("OctalInteger") private static final int MODE_WORLD_READABLE = 0444;
	
	private static final String CRANE_ENV_MACHINE_NAME = "SALVAGE_MACHINE_NAME";
	private static final String CRANE_ENV_CRANE_NAME = "SALVAGE_CRANE_NAME";
	private static final String CRANE_ENV_VOLUME_NAME = "SALVAGE_VOLUME_NAME";
	private static final String CRANE_ENV_TIDE_TIMESTAMP = "SALVAGE_TIDE_TIMESTAMP";
	
	private static final String FILE_PATH_META = "/salvage/meta/meta.json";
	private static final String FILE_PATH_VOLUME = "/salvage/volume";
	
	private static final Gson GSON = new GsonBuilder()
			.disableHtmlEscaping()
			.setPrettyPrinting()
			.create();
	
	private final DockerClient docker;
	private final SalvageVolume volume;
	private final SalvageCrane crane;
	private final BackupMeta meta;
	private final VolumeLog volumeLog;
	
	public SalvageVessel(DockerClient docker, SalvageVolume volume, SalvageCrane crane, BackupMeta.HostMeta hostMeta, VolumeLog volumeLog) {
		this.docker = docker;
		this.volume = volume;
		this.crane = crane;
		this.volumeLog = volumeLog;
		
		meta = new BackupMeta(hostMeta, volume.meta(), crane.name(), crane.image());
	}
	
	public void start() throws Throwable {
		var env = new HashMap<>(crane.env());
		env.put(CRANE_ENV_MACHINE_NAME, meta.hostMeta().host());
		env.put(CRANE_ENV_CRANE_NAME, meta.crane());
		env.put(CRANE_ENV_VOLUME_NAME, volume.name());
		//noinspection MagicNumber
		env.put(CRANE_ENV_TIDE_TIMESTAMP, String.valueOf(meta.hostMeta().executionStart() / 1000L));
		
		var container = docker.createContainerCmd(crane.image())
				.withEnv(prepareEnv(env))
				.withLabels(Map.of(SalvageService.SALVAGE_ENTITY_LABEL, "crane"))
				.withStopTimeout(BACKUP_SHUTDOWN_GRACE_TIMEOUT)
				.withHostConfig(HostConfig.newHostConfig()
						// Keep exit status available until it has been observed.
						.withAutoRemove(false)
						.withBinds(prepareBinds()))
				.exec();
		log.info("created container '{}' for crane '{}' to backup volume '{}'", container.getId(), crane.name(), volume.name());
		
		Throwable failure = null;
		try {
			startBackupContainer(container);
		} catch (Throwable e) {
			failure = e;
			throw e;
		} finally {
			// Docker's HTTP client rejects calls from an interrupted thread.
			boolean interrupted = Thread.interrupted();
			try {
				try {
					var state = docker.inspectContainerCmd(container.getId()).exec().getState();
					if (Boolean.TRUE.equals(state.getRunning())) {
						try {
							docker.stopContainerCmd(container.getId()).withTimeout(5).exec();
						} finally {
							docker.removeContainerCmd(container.getId()).withForce(true).exec();
						}
					} else {
						docker.removeContainerCmd(container.getId()).exec();
					}
				} catch (NotFoundException ignored) {
					// Already removed by an external administrator.
				}
			} catch (Throwable cleanupError) {
				if (failure != null) failure.addSuppressed(cleanupError);
				else throw cleanupError;
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		}
	}

	private void startBackupContainer(CreateContainerResponse container) throws Throwable {
		// upload metadata into container, so they will be backed up by the crane
		byte[] metaTar = createMetaArchive(meta);
		docker.copyArchiveToContainerCmd(container.getId())
				.withTarInputStream(new ByteArrayInputStream(metaTar))
				.withRemotePath("/")
				.exec();
		log.trace("uploaded meta data to container '{}': {}", container.getId(), meta);
		
		try (var frameCallback = docker.attachContainerCmd(container.getId())
				.withStdOut(true)
				.withStdErr(true)
				.withFollowStream(true)
				.exec(new FrameCallback(frame -> {
					var line = new String(frame.getPayload(), StandardCharsets.UTF_8).trim();
					volumeLog.log(line);
					log.debug("[{}@{}] {}", volume.name(), crane.name(), line);
				}))) {
		log.trace("starting backup container '{}' for volume '{}'", container.getId(), volume.name());
		docker.startContainerCmd(container.getId()).exec();
		try (var waitCallback = docker.waitContainerCmd(container.getId()).exec(new WaitContainerResultCallback())) {
		
		// docker-java eats interrupted exception, so use our own callback first (still doesn't fully address the problem)
		frameCallback.join();
		var statusCode = waitCallback.awaitStatusCode();
		if (statusCode != 0) {
			throw new RuntimeException("backup of volume '" + volume.name() + "' failed with exit code " + statusCode);
		}
		}
		}
	}
	
	private List<Bind> prepareBinds() {
		
		// WARNING: docker-java is a dumpsterfire and completly misunderstands how volumes and binds work, the following code is correct
		var binds = new ArrayList<Bind>();
		
		// mount volume as ro for backup
		binds.add(new Bind(volume.name(), new Volume(FILE_PATH_VOLUME), AccessMode.ro, SELContext.DEFAULT, true));
		
		// add crane specific volumes
		for (var mount : crane.mounts().entrySet())
			binds.add(new Bind(mount.getKey(), new Volume(mount.getValue()), AccessMode.rw, SELContext.DEFAULT, false));
		
		return binds;
	}
	
	private static List<String> prepareEnv(Map<String, String> env) {
		var result = new ArrayList<String>();
		for (var entry : env.entrySet())
			result.add(entry.getKey() + "=" + entry.getValue());
		return result;
	}
	
	private static byte[] createMetaArchive(BackupMeta meta) throws IOException {
		// convert meta data to json
		var json = GSON.toJson(meta);
		var jsonBytes = json.getBytes(StandardCharsets.UTF_8);
		
		var out = new ByteArrayOutputStream();
		try (var tar = new TarArchiveOutputStream(out)) {
			var entry = new TarArchiveEntry(FILE_PATH_META);
			entry.setUserId(ROOT_UID);
			entry.setGroupId(ROOT_GID);
			entry.setMode(MODE_WORLD_READABLE);
			entry.setSize(jsonBytes.length);
			tar.putArchiveEntry(entry);
			tar.write(jsonBytes);
			tar.closeArchiveEntry();
		}
		return out.toByteArray();
	}
}
