package me.desair.tus.server.upload.s3;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.upload.UploadLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MinIO S3-backed implementation of {@link UploadLock} that holds an exclusive lock lease on an
 * upload resource using S3 objects. Spawns a heartbeat thread to auto-renew the lock lease until
 * closed.
 */
public class S3UploadLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(S3UploadLock.class);

  private final MinioClient minioClient;
  private final String bucket;
  private final String lockKey;
  private final String stopKey;
  private final String holderId;
  private final long leaseDurationMs;
  private final ScheduledExecutorService heartbeatExecutor;
  private final String requestUri;
  private final Map<String, InputStream> inputStreamMap;

  /**
   * Constructs a new S3UploadLock instance using MinIO Java SDK.
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
    this.heartbeatExecutor = heartbeatExecutor;
  }

  /** Gets the holder ID for this lock. */
  public String getHolderId() {
    return holderId;
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
    try {
      heartbeatExecutor.shutdownNow();
    } catch (Exception e) {
      log.debug("Error shutting down lock heartbeat executor", e);
    }

    if (inputStreamMap != null && requestUri != null) {
      inputStreamMap.remove(requestUri);
    }

    deleteS3ObjectQuietly(lockKey);
    deleteS3ObjectQuietly(stopKey);
  }

  void renewLease() {
    try {
      long newExpiry = System.currentTimeMillis() + leaseDurationMs;
      String lockContent =
          String.format(
              "{\"holder\":\"%s\",\"expiresAt\":%d,\"acquiredAt\":%d}",
              holderId, newExpiry, System.currentTimeMillis());
      byte[] lockContentBytes = lockContent.getBytes(StandardCharsets.UTF_8);

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(lockKey).stream(
                  new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
              .build());
    } catch (Exception e) {
      log.warn("Failed to renew S3 lock lease for key {}", lockKey, e);
    }
  }

  private void deleteS3ObjectQuietly(String key) {
    if (key == null) {
      return;
    }
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 lock object {}", key, e);
    }
  }
}
