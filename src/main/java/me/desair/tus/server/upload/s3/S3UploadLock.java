package me.desair.tus.server.upload.s3;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import me.desair.tus.server.upload.AbstractLeaseLock;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.S3UploadLockJsonSerializer;
import org.apache.commons.lang3.Strings;
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
 *       heartbeat thread and deletes both the {@code .lock} lease object (if still owned) and any
 *       lingering {@code .stop} signal objects from S3.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class S3UploadLock extends AbstractLeaseLock {

  private static final Logger log = LoggerFactory.getLogger(S3UploadLock.class);

  private String bucket;
  private String lockKey;
  private String stopKey;

  @JsonIgnore private MinioClient minioClient;

  /** Default constructor for Jackson JSON deserialization. */
  public S3UploadLock() {
    super();
  }

  /** Constructor for serializing lock lease metadata to S3. */
  public S3UploadLock(
      String holderId,
      String requestUri,
      String bucket,
      String lockKey,
      String stopKey,
      long leaseDurationMs,
      long expiresAt) {
    super(holderId, requestUri, leaseDurationMs, expiresAt);
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
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
    super(holderId, leaseDurationMs, requestUri, inputStreamMap, "s3-lock-heartbeat");
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
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
    super(
        holderId,
        leaseDurationMs,
        requestUri,
        inputStreamMap,
        heartbeatExecutor,
        "s3-lock-heartbeat");
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
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

  @Override
  protected void doRenewLease() {
    try {
      byte[] lockContentBytes = S3UploadLockJsonSerializer.serializeToBytes(this);

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(lockKey).stream(
                  new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
              .build());
    } catch (Exception e) {
      log.warn("Failed to renew S3 lock lease for key {}", lockKey, e);
    }
  }

  @Override
  protected void releaseLockResource() {
    // Owner-Safe Lock Release: only delete .lock lease if it is still owned by this holder
    deleteS3LockObjectIfOwner(lockKey);
    deleteS3ObjectQuietly(stopKey);
  }

  void deleteS3LockObjectIfOwner(String key) {
    if (key == null || minioClient == null || bucket == null) {
      return;
    }
    try {
      // Re-verify that the remote lock object is still owned by this lock handle before deleting it
      try (InputStream stream =
          minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
        S3UploadLock remoteLock = S3UploadLockJsonSerializer.deserialize(stream);
        if (remoteLock != null && !Strings.CS.equals(remoteLock.getHolderId(), getHolderId())) {
          log.info(
              "Skipping deletion of S3 lock key {}: lock is currently held by another node {}",
              key,
              remoteLock.getHolderId());
          return;
        }
      } catch (ErrorResponseException e) {
        // Object is already gone or missing
        return;
      }
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 lock object {}", key, e);
    }
  }

  private void deleteS3ObjectQuietly(String key) {
    if (key == null || minioClient == null || bucket == null) {
      return;
    }
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object {}", key, e);
    }
  }
}
