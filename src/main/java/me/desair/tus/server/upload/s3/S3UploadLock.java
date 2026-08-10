package me.desair.tus.server.upload.s3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.S3UploadLockJsonSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MinIO S3-backed implementation of {@link UploadLock} that holds an exclusive lock lease on an
 * upload resource using S3 objects. Serves both as the JSON payload representation stored in S3 and
 * the active lock handle with heartbeat lease auto-renewal.
 *
 * <p><b>Why Lease Renewal is Required (S3 vs File-Based Locking):</b>
 *
 * <ul>
 *   <li><b>File-Based Locks (OS Kernel Managed)</b>: {@code FileBasedLock} uses OS file channel
 *       locks ({@link java.nio.channels.FileLock}). When a JVM process crashes or is killed, the OS
 *       kernel automatically closes open file descriptors and releases the lock. Thus, no lock
 *       expiration or renewal is needed.
 *   <li><b>S3 Locks (Stateless HTTP Storage)</b>: S3 has no persistent process connections or OS
 *       file descriptors. Locks are stored as S3 objects ({@code .lock}). To prevent crashed pods
 *       from leaving permanently orphaned lock objects, S3 locks use a short Time-To-Live (TTL)
 *       lease.
 *   <li><b>Heartbeat Renewal Requirement</b>: Because TUS uploads can stream for minutes or hours,
 *       a background daemon thread periodically renews the lease ({@link #renewLease()}). Removing
 *       lease renewal would either cause locks to expire mid-upload on long streams (leading to
 *       race conditions and data corruption) or cause pod crashes to permanently deadlock upload
 *       IDs.
 * </ul>
 *
 * <p>Lock Lease Mechanics for Developers:
 *
 * <ul>
 *   <li><b>Heartbeat Lease Renewal</b>: When initialized, a background daemon thread executes
 *       {@link #renewLease()} at a periodic interval (one-third of {@code leaseDurationMs}, e.g.
 *       every 10s for a 30s lease).
 *   <li><b>Clean Lock Release</b>: When the HTTP request finishes, {@link #close()} shuts down the
 *       heartbeat thread and deletes both the {@code .lock} lease object and any lingering {@code
 *       .stop} signal objects from S3.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class S3UploadLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(S3UploadLock.class);

  private String holderId;
  private String requestUri;
  private String bucket;
  private String lockKey;
  private String stopKey;
  private long leaseDurationMs;
  private long expiresAt;
  private long acquiredAt;

  @JsonIgnore private MinioClient minioClient;
  @JsonIgnore private ScheduledExecutorService heartbeatExecutor;
  @JsonIgnore private Map<String, InputStream> inputStreamMap;

  /** Default constructor for Jackson JSON deserialization. */
  public S3UploadLock() {}

  /** Constructor for serializing lock lease metadata to S3. */
  public S3UploadLock(
      String holderId,
      String requestUri,
      String bucket,
      String lockKey,
      String stopKey,
      long leaseDurationMs,
      long expiresAt) {
    this.holderId = holderId;
    this.requestUri = requestUri;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
    this.leaseDurationMs = leaseDurationMs;
    this.expiresAt = expiresAt;
    this.acquiredAt = System.currentTimeMillis();
  }

  /**
   * Constructs a new S3UploadLock instance using MinIO Java SDK and starts the lease renewal
   * daemon.
   *
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket
   * @param lockKey The S3 object key for the lock lease
   * @param stopKey The S3 object key for the interrupt stop signal
   * @param holderId Unique ID identifying the lock holder
   * @param leaseDurationMs Lease duration in milliseconds
   * @param requestUri The request URI linked to this lock
   * @param inputStreamMap Map of active request input streams
   */
  public S3UploadLock(
      MinioClient minioClient,
      String bucket,
      String lockKey,
      String stopKey,
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> inputStreamMap) {
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
    this.holderId = holderId;
    this.leaseDurationMs = leaseDurationMs;
    this.requestUri = requestUri;
    this.inputStreamMap = inputStreamMap;
    this.acquiredAt = System.currentTimeMillis();
    this.expiresAt = this.acquiredAt + leaseDurationMs;

    // Run heartbeat lease renewal at 1/3 of the lease duration (e.g., every 10 seconds for a 30s
    // lease)
    long heartbeatPeriodMs = Math.max(1000L, leaseDurationMs / 3);
    this.heartbeatExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "s3-lock-heartbeat-" + holderId);
              t.setDaemon(true);
              return t;
            });
    this.heartbeatExecutor.scheduleAtFixedRate(
        this::renewLease, heartbeatPeriodMs, heartbeatPeriodMs, TimeUnit.MILLISECONDS);
  }

  S3UploadLock(
      MinioClient minioClient,
      String bucket,
      String lockKey,
      String stopKey,
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> inputStreamMap,
      ScheduledExecutorService heartbeatExecutor) {
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
    this.holderId = holderId;
    this.leaseDurationMs = leaseDurationMs;
    this.requestUri = requestUri;
    this.inputStreamMap = inputStreamMap;
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

  public String getBucket() {
    return bucket;
  }

  public void setBucket(String bucket) {
    this.bucket = bucket;
  }

  public String getLockKey() {
    return lockKey;
  }

  public void setLockKey(String lockKey) {
    this.lockKey = lockKey;
  }

  public String getStopKey() {
    return stopKey;
  }

  public void setStopKey(String stopKey) {
    this.stopKey = stopKey;
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
    // Step 1: Stop the background heartbeat daemon thread
    if (heartbeatExecutor != null) {
      try {
        heartbeatExecutor.shutdownNow();
      } catch (Exception e) {
        log.debug("Error shutting down lock heartbeat executor", e);
      }
    }

    // Step 2: Remove active request stream registration
    if (inputStreamMap != null && requestUri != null) {
      inputStreamMap.remove(requestUri);
    }

    // Step 3: Delete .lock lease object and .stop contention signal object from S3
    deleteS3ObjectQuietly(lockKey);
    deleteS3ObjectQuietly(stopKey);
  }

  /** Renew the lock lease in S3 by updating the expiration timestamp. */
  void renewLease() {
    try {
      this.expiresAt = System.currentTimeMillis() + leaseDurationMs;
      byte[] lockContentBytes = S3UploadLockJsonSerializer.serializeToBytes(this);

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(lockKey).stream(
                  new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
              .build());
    } catch (Exception e) {
      log.warn("Failed to renew S3 lock lease for key {}", lockKey, e);
    }
  }

  private void deleteS3ObjectQuietly(String key) {
    if (key == null || minioClient == null || bucket == null) {
      return;
    }
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 lock object {}", key, e);
    }
  }
}
