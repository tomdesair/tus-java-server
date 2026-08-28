package me.desair.tus.server.upload.s3;

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
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MinIO S3-backed implementation of {@link UploadLock} that holds an exclusive lock lease on an
 * upload resource using S3 objects.
 *
 * <p>Wraps an active lease and maintains a background daemon executor that periodically renews the
 * lease metadata in S3 to prevent lock expiry during long-running streaming uploads.
 */
public class S3UploadLock extends AbstractLeaseLock {

  private static final Logger log = LoggerFactory.getLogger(S3UploadLock.class);

  private final String bucket;
  private final String lockKey;
  private final String stopKey;
  private final MinioClient minioClient;

  /**
   * Constructs a new S3UploadLock instance using MinIO Java SDK and starts the lease renewal
   * daemon.
   *
   * @param leaseData The lease metadata
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket
   * @param lockKey The S3 object key for the lock lease
   * @param stopKey The S3 object key for the interrupt stop signal
   * @param inputStreamMap Map of active request input streams
   */
  public S3UploadLock(
      LeaseData leaseData,
      MinioClient minioClient,
      String bucket,
      String lockKey,
      String stopKey,
      Map<String, InputStream> inputStreamMap) {
    this(leaseData, minioClient, bucket, lockKey, stopKey, inputStreamMap, null);
  }

  S3UploadLock(
      LeaseData leaseData,
      MinioClient minioClient,
      String bucket,
      String lockKey,
      String stopKey,
      Map<String, InputStream> inputStreamMap,
      ScheduledExecutorService heartbeatExecutor) {
    super(leaseData, inputStreamMap, heartbeatExecutor, "s3-lock-heartbeat");
    this.minioClient = minioClient;
    this.bucket = bucket;
    this.lockKey = lockKey;
    this.stopKey = stopKey;
  }

  public String getBucket() {
    return bucket;
  }

  public String getLockKey() {
    return lockKey;
  }

  public String getStopKey() {
    return stopKey;
  }

  @Override
  protected void doRenewLease() {
    if (minioClient == null || lockKey == null) {
      return;
    }
    try {
      if (!doesLockOwnershipMatch(lockKey)) {
        log.info(
            "Skipping renewal of S3 lock key {}: lock was taken over by another node", lockKey);
        return;
      }

      getLeaseData().setExpiresAt(getExpiresAt());
      byte[] lockContentBytes = LeaseDataJsonSerializer.serializeToBytes(getLeaseData());

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
      if (!doesLockOwnershipMatch(key)) {
        log.info(
            "Skipping deletion of S3 lock key {}: lock is currently held by another node", key);
        return;
      }
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 lock object {}", key, e);
    }
  }

  /**
   * Verifies that the S3 lock object is still owned by this lock holder.
   *
   * @param key The S3 lock object key
   * @return {@code true} if the lock object is missing or owned by this holder; {@code false} if
   *     owned by a different holder or arguments are invalid
   */
  boolean doesLockOwnershipMatch(String key) {
    if (key == null || minioClient == null || bucket == null) {
      return false;
    }
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
      LeaseData remoteLock = LeaseDataJsonSerializer.deserialize(stream);
      if (remoteLock != null && !Strings.CS.equals(remoteLock.getHolderId(), getHolderId())) {
        return false;
      }
    } catch (ErrorResponseException e) {
      // Object is already gone or missing, safe to proceed
    } catch (Exception e) {
      log.debug("Error checking lock ownership for S3 key {}", key, e);
    }
    return true;
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
