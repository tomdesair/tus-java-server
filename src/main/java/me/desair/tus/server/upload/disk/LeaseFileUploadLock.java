package me.desair.tus.server.upload.disk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import me.desair.tus.server.upload.AbstractLeaseLock;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A distributed lease-based implementation of {@link UploadLock} that holds an exclusive lock lease
 * on an upload resource via an atomic directory and a JSON lease file. Serves both as the JSON
 * payload representation stored in {@code lease.json} on disk/NFS and the active lock handle with
 * heartbeat lease auto-renewal.
 *
 * <p><b>Why Lease Renewal is Required (NFS & Shared Drives vs Local FileLock):</b>
 *
 * <ul>
 *   <li><b>Local OS FileLock</b>: Relies on OS kernel file descriptor tracking (POSIX {@code fcntl}
 *       / Windows byte-range lock). When a process crashes, the OS cleans up file descriptors.
 *       However, network filesystems (NFSv3/v4, AWS EFS, SMB/CIFS) frequently drop or stall lock
 *       state, fail in unprivileged container network namespaces without {@code statd}, or hang
 *       when mounted with {@code nolock}.
 *   <li><b>Lease File Locking</b>: Replaces OS locks with an atomic directory and a short
 *       Time-To-Live (TTL) lease JSON file. If a node crashes unexpectedly (e.g. OOM killer, {@code
 *       kill -9}, network partition), the lease auto-expires within the TTL (default 30s), allowing
 *       peer cluster replicas to evict and re-acquire it cleanly.
 *   <li><b>Heartbeat Renewal</b>: Because TUS uploads can stream for minutes or hours, a background
 *       daemon thread periodically updates {@code expiresAt} in {@code lease.json} every {@code
 *       leaseDuration / 3} milliseconds.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LeaseFileUploadLock extends AbstractLeaseLock {

  private static final Logger log = LoggerFactory.getLogger(LeaseFileUploadLock.class);

  private String storagePath;

  @JsonIgnore private Path lockDirPath;
  @JsonIgnore private Path stopFilePath;

  /** Default constructor for Jackson JSON deserialization. */
  public LeaseFileUploadLock() {
    super();
  }

  /**
   * Constructs an active LeaseFileUploadLock and starts the background heartbeat lease renewal
   * daemon.
   *
   * @param lockDirPath Dedicated lock directory path
   * @param stopFilePath Lock contention stop signal file path
   * @param holderId Unique identifier of the lock holder
   * @param leaseDurationMs Lease duration in milliseconds
   * @param requestUri Target upload URI
   * @param activeInputStreams Map of active request input streams in the JVM
   */
  public LeaseFileUploadLock(
      Path lockDirPath,
      Path stopFilePath,
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> activeInputStreams) {
    super(holderId, leaseDurationMs, requestUri, activeInputStreams, "lease-file-lock-heartbeat");
    this.lockDirPath = lockDirPath;
    this.stopFilePath = stopFilePath;
    this.storagePath = lockDirPath != null ? lockDirPath.toString() : null;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  @Override
  protected void doRenewLease() {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return;
    }
    try {
      Path leaseTmpFile = lockDirPath.resolve("lease.json.tmp." + UUID.randomUUID());
      Path leaseFile = lockDirPath.resolve("lease.json");
      Utils.writeJson(this, leaseTmpFile, false);
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
