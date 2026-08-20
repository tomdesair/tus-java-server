package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import me.desair.tus.server.upload.AbstractLeaseLock;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
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

  @Override
  protected void doRenewLease() {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return;
    }
    try {
      Path leaseTmpFile = lockDirPath.resolve("lease.json.tmp." + UUID.randomUUID());
      Path leaseFile = lockDirPath.resolve("lease.json");
      leaseData.setExpiresAt(getExpiresAt());
      LeaseDataJsonSerializer.serializeToPath(leaseData, leaseTmpFile);
      Files.move(
          leaseTmpFile,
          leaseFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      log.warn("Failed to renew lease for lock directory {}", lockDirPath, e);
    }
  }

  @Override
  protected void releaseLockResource() {
    // 1. Delete lease file and lock directory
    if (lockDirPath != null) {
      try {
        Files.deleteIfExists(lockDirPath.resolve("lease.json"));
        Files.deleteIfExists(lockDirPath);
      } catch (IOException e) {
        log.warn("Failed to remove lock directory {}", lockDirPath, e);
      }
    }

    // 2. Delete .stop signal file if present
    if (stopFilePath != null) {
      try {
        Files.deleteIfExists(stopFilePath);
      } catch (IOException ignored) {
        // Safe to ignore stop file deletion failure
      }
    }
  }
}
