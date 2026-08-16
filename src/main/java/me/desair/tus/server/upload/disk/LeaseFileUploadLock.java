package me.desair.tus.server.upload.disk;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
public class LeaseFileUploadLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(LeaseFileUploadLock.class);

  private String holderId;
  private String requestUri;
  private String storagePath;
  private long leaseDurationMs;
  private long expiresAt;
  private long acquiredAt;

  @JsonIgnore private Path lockDirPath;
  @JsonIgnore private Path stopFilePath;
  @JsonIgnore private ScheduledExecutorService heartbeatExecutor;
  @JsonIgnore private Map<String, InputStream> activeInputStreams;

  /** Default constructor for Jackson JSON deserialization. */
  public LeaseFileUploadLock() {}

  /**
   * Constructor for serializing lease metadata to JSON.
   *
   * @param holderId Unique identifier of the lock holder
   * @param requestUri Target upload URI
   * @param storagePath Storage directory path
   * @param leaseDurationMs Lease duration in milliseconds
   * @param expiresAt Absolute expiration timestamp in milliseconds
   */
  public LeaseFileUploadLock(
      String holderId,
      String requestUri,
      String storagePath,
      long leaseDurationMs,
      long expiresAt) {
    this.holderId = holderId;
    this.requestUri = requestUri;
    this.storagePath = storagePath;
    this.leaseDurationMs = leaseDurationMs;
    this.expiresAt = expiresAt;
    this.acquiredAt = System.currentTimeMillis();
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
    this(
        lockDirPath, stopFilePath, holderId, leaseDurationMs, requestUri, activeInputStreams, null);

    // Calculate renewal interval: leaseDuration / 3 (e.g. every 10s for 30s lease, min 1s)
    long renewalPeriodMs = Math.max(1000L, leaseDurationMs / 3);
    this.heartbeatExecutor =
        Utils.scheduleWatchdog(
            "lease-file-lock-heartbeat-" + holderId,
            this::renewLease,
            renewalPeriodMs,
            renewalPeriodMs,
            TimeUnit.MILLISECONDS);
  }

  LeaseFileUploadLock(
      Path lockDirPath,
      Path stopFilePath,
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor) {
    this.lockDirPath = lockDirPath;
    this.stopFilePath = stopFilePath;
    this.holderId = holderId;
    this.leaseDurationMs = leaseDurationMs;
    this.requestUri = requestUri;
    this.activeInputStreams = activeInputStreams;
    this.storagePath = lockDirPath != null ? lockDirPath.toString() : null;
    this.acquiredAt = System.currentTimeMillis();
    this.expiresAt = this.acquiredAt + leaseDurationMs;
    this.heartbeatExecutor = heartbeatExecutor;
  }

  public String getHolderId() {
    return holderId;
  }

  public void setHolderId(String holderId) {
    this.holderId = holderId;
  }

  public String getRequestUri() {
    return requestUri;
  }

  public void setRequestUri(String requestUri) {
    this.requestUri = requestUri;
  }

  public String getStoragePath() {
    return storagePath;
  }

  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  public long getLeaseDurationMs() {
    return leaseDurationMs;
  }

  public void setLeaseDurationMs(long leaseDurationMs) {
    this.leaseDurationMs = leaseDurationMs;
  }

  public long getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(long expiresAt) {
    this.expiresAt = expiresAt;
  }

  public long getAcquiredAt() {
    return acquiredAt;
  }

  public void setAcquiredAt(long acquiredAt) {
    this.acquiredAt = acquiredAt;
  }

  @Override
  public String getUploadUri() {
    return requestUri;
  }

  @Override
  public void release() {
    close();
  }

  @Override
  public void close() {
    // 1. Terminate heartbeat daemon cleanly via centralized Utils helper
    Utils.shutdownExecutor(heartbeatExecutor);

    // 2. Remove active stream registration from JVM heap
    if (activeInputStreams != null && requestUri != null) {
      activeInputStreams.remove(requestUri);
    }

    // 3. Delete lease file and lock directory
    if (lockDirPath != null) {
      try {
        Files.deleteIfExists(lockDirPath.resolve("lease.json"));
        Files.deleteIfExists(lockDirPath);
      } catch (IOException e) {
        log.warn("Failed to remove lock directory {}", lockDirPath, e);
      }
    }

    // 4. Delete .stop signal file if present
    if (stopFilePath != null) {
      try {
        Files.deleteIfExists(stopFilePath);
      } catch (IOException ignored) {
        // Safe to ignore stop file deletion failure
      }
    }
  }

  /**
   * Renew the lock lease in the lock directory by updating the expiration timestamp and writing the
   * updated metadata to {@code lease.json}.
   */
  void renewLease() {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return;
    }
    try {
      this.expiresAt = System.currentTimeMillis() + leaseDurationMs;
      Path leaseTmpFile = lockDirPath.resolve("lease.json.tmp." + java.util.UUID.randomUUID());
      Path leaseFile = lockDirPath.resolve("lease.json");
      Utils.writeJson(this, leaseTmpFile, false);
      Files.move(
          leaseTmpFile,
          leaseFile,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      log.warn("Failed to renew lease for lock directory {}", lockDirPath, e);
    }
  }
}
