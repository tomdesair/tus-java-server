package me.desair.tus.server.upload.disk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import me.desair.tus.server.upload.AbstractLeaseLock;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed upload lock implementation backed by atomic directory leases on the local or shared
 * filesystem.
 *
 * <p>Wraps an active lease and maintains a background daemon executor that periodically renews the
 * lease metadata on disk to prevent lock expiry during long-running streaming uploads.
 */
public class LeaseFileUploadLock extends AbstractLeaseLock {

  private static final Logger log = LoggerFactory.getLogger(LeaseFileUploadLock.class);

  private final Path lockDirPath;
  private final Path stopFilePath;
  private final LeaseData leaseData;

  /**
   * Constructs an active {@link LeaseFileUploadLock} and starts the background heartbeat lease
   * renewal daemon.
   *
   * @param leaseData The lease metadata
   * @param lockDirPath Dedicated lock directory path
   * @param stopFilePath Lock contention stop signal file path
   * @param activeInputStreams Map of active request input streams in the JVM
   */
  public LeaseFileUploadLock(
      LeaseData leaseData,
      Path lockDirPath,
      Path stopFilePath,
      Map<String, InputStream> activeInputStreams) {
    this(leaseData, lockDirPath, stopFilePath, activeInputStreams, null);
  }

  /**
   * Testing constructor allowing injection of a custom heartbeat executor.
   *
   * @param leaseData The lease metadata
   * @param lockDirPath Dedicated lock directory path
   * @param stopFilePath Lock contention stop signal file path
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param heartbeatExecutor Custom executor service for heartbeats
   */
  LeaseFileUploadLock(
      LeaseData leaseData,
      Path lockDirPath,
      Path stopFilePath,
      Map<String, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor) {
    super(leaseData, activeInputStreams, heartbeatExecutor, "lease-file-lock-heartbeat");
    this.leaseData = leaseData;
    this.lockDirPath = lockDirPath;
    this.stopFilePath = stopFilePath;
  }

  public LeaseData getLeaseData() {
    return leaseData;
  }

  public String getStoragePath() {
    return lockDirPath != null ? lockDirPath.toString() : null;
  }

  /**
   * Renews the active lease by advancing {@code expiresAt} and persisting the updated metadata to
   * disk under the protection of the {@link LeaseFileMutex}. Includes ownership verification to
   * ensure a paused or ungracefully expired holder does not overwrite a successor's active lease.
   */
  @Override
  protected void doRenewLease() {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return;
    }
    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDirPath)) {
      if (!mutex.isAcquired()) {
        return;
      }
      if (!doesLockOwnershipMatch()) {
        log.info("Lease for {} was taken over by another holder. Aborting renewal.", lockDirPath);
        return;
      }

      Path leaseFile = lockDirPath.resolve("lease.json");
      leaseData.setExpiresAt(getExpiresAt());
      LeaseDataJsonSerializer.serializeToPath(leaseData, leaseFile);
    } catch (Exception e) {
      log.warn("Failed to renew lease for lock directory {}", lockDirPath, e);
    }
  }

  /**
   * Releases the lock resource upon request completion. Synchronized via the {@link LeaseFileMutex}
   * and verifies {@code holderId} ownership so that an expired or paused holder does not delete a
   * successor's lock directory.
   */
  @Override
  protected void releaseLockResource() {
    if (lockDirPath != null && Files.exists(lockDirPath)) {
      try (LeaseFileMutex mutex = new LeaseFileMutex(lockDirPath)) {
        if (mutex.isAcquired() && doesLockOwnershipMatch()) {
          Utils.deletePathQuietly(lockDirPath.resolve("lease.json"));
          Utils.deletePathQuietly(lockDirPath);
        }
      }
    }

    // 2. Delete .stop signal file if present
    if (stopFilePath != null) {
      Utils.deletePathQuietly(stopFilePath);
    }
  }

  /**
   * Verifies that the on-disk {@code lease.json} file is still owned by this lock holder.
   *
   * @return {@code true} if the lease file does not exist, is unparseable, or matches this holder's
   *     ID; {@code false} if owned by a different holder
   */
  boolean doesLockOwnershipMatch() {
    if (lockDirPath == null) {
      return false;
    }
    Path leaseFile = lockDirPath.resolve("lease.json");
    if (Files.exists(leaseFile)) {
      try {
        LeaseData existingLease = LeaseDataJsonSerializer.deserialize(leaseFile);
        if (existingLease != null
            && !Strings.CS.equals(existingLease.getHolderId(), getHolderId())) {
          return false;
        }
      } catch (Exception ignored) {
        // If unparseable or corrupt, consider owned/proceed
      }
    }
    return true;
  }
}
