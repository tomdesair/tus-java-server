package me.desair.tus.server.upload.disk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import me.desair.tus.server.upload.AbstractLeaseLock;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.upload.UploadId;
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

  /**
   * Constructs an active {@link LeaseFileUploadLock} and starts the background heartbeat lease
   * renewal daemon.
   *
   * @param leaseData The lease metadata
   * @param uploadId The upload identifier associated with this lock
   * @param lockDirPath Dedicated lock directory path
   * @param stopFilePath Lock contention stop signal file path
   * @param activeInputStreams Map of active request input streams in the JVM
   */
  public LeaseFileUploadLock(
      LeaseData leaseData,
      UploadId uploadId,
      Path lockDirPath,
      Path stopFilePath,
      Map<UploadId, InputStream> activeInputStreams) {
    this(leaseData, uploadId, lockDirPath, stopFilePath, activeInputStreams, null);
  }

  /**
   * Testing constructor allowing injection of a custom heartbeat executor.
   *
   * @param leaseData The lease metadata
   * @param uploadId The upload identifier associated with this lock
   * @param lockDirPath Dedicated lock directory path
   * @param stopFilePath Lock contention stop signal file path
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param heartbeatExecutor Custom executor service for heartbeats
   */
  LeaseFileUploadLock(
      LeaseData leaseData,
      UploadId uploadId,
      Path lockDirPath,
      Path stopFilePath,
      Map<UploadId, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor) {
    super(leaseData, uploadId, activeInputStreams, heartbeatExecutor);
    this.lockDirPath = lockDirPath;
    this.stopFilePath = stopFilePath;
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
  protected void doRenewLease() throws Exception {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      throw new IllegalStateException("Lock directory does not exist: " + lockDirPath);
    }
    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDirPath)) {
      if (!mutex.isAcquired()) {
        log.warn("Failed to acquire mutex to renew lease for {}", lockDirPath);
        return;
      }
      Path leaseFile = lockDirPath.resolve("lease.json");
      // If another node took over the lease, abort renewal by throwing IllegalStateException
      // so AbstractLeaseLock terminates the heartbeat and aborts the active stream.
      if (Files.exists(leaseFile) && !doesLockOwnershipMatch()) {
        log.info("Lease for {} was taken over by another holder. Aborting renewal.", lockDirPath);
        throw new IllegalStateException(
            "Lease for " + lockDirPath + " was taken over by another holder");
      }

      getLeaseData().setExpiresAt(getExpiresAt());
      LeaseDataJsonSerializer.serializeToPath(getLeaseData(), leaseFile);
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
        if (mutex.isAcquired()) {
          Path leaseFile = lockDirPath.resolve("lease.json");
          if (!Files.exists(leaseFile) || doesLockOwnershipMatch()) {
            Utils.deletePathQuietly(leaseFile);
            Utils.deletePathQuietly(lockDirPath);
          }
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
   * <p>Fail-closed split-brain protection: returns {@code true} only when the lease file exists, is
   * parseable, and its holder ID matches this lock holder. Returns {@code false} if the file is
   * missing, unreadable, or owned by a different holder.
   *
   * @return {@code true} if the lease file exists and matches this holder's ID; {@code false}
   *     otherwise
   */
  boolean doesLockOwnershipMatch() {
    if (lockDirPath == null) {
      return false;
    }
    Path leaseFile = lockDirPath.resolve("lease.json");
    if (!Files.exists(leaseFile)) {
      return false;
    }
    try {
      LeaseData existingLease = LeaseDataJsonSerializer.deserialize(leaseFile);
      return existingLease != null && Strings.CS.equals(existingLease.getHolderId(), getHolderId());
    } catch (Exception e) {
      log.warn("Error reading lease file {} for ownership verification", leaseFile, e);
      return false;
    }
  }
}
