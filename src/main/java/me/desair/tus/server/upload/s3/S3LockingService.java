package me.desair.tus.server.upload.s3;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Objects;
import me.desair.tus.server.upload.AbstractLeaseLockingService;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed S3-backed implementation of {@link UploadLockingService} using the MinIO Java SDK.
 *
 * <p>Key Architecture Features & S3/MinIO Developer Guide:
 *
 * <ul>
 *   <li><b>Distributed Lock Lease Objects</b>: Locks are represented as small JSON lease objects
 *       written to S3 under {@code <locksPrefix>/<UploadId>.lock}. Each lease object records a
 *       unique {@code holderId} and an absolute timestamp {@code expiresAt}.
 *   <li><b>Atomic Lock Acquisition</b>: When an upload request arrives, the server checks whether
 *       an unexpired lock object already exists in S3. If no active lock is found, a new lock
 *       object is written to S3, granting exclusive ownership to the current thread/pod without
 *       requiring Redis or an external database.
 *   <li><b>Heartbeat & Lease Auto-Renewal</b>: Managed locks spawn background daemon threads that
 *       periodically update the lock object in S3, keeping the lease active while long uploads run.
 *   <li><b>Cross-Pod Lock Contention & Interrupt Signals</b>: When a concurrent request arrives for
 *       a locked upload (e.g., HEAD or DELETE while a PATCH is streaming data on another pod), the
 *       service writes a {@code <locksPrefix>/<UploadId>.stop} signal object to S3. A background
 *       watchdog thread on the pod holding the lock detects the {@code .stop} file and interrupts
 *       the active input stream immediately, resolving lock contention cleanly across Kubernetes
 *       pods.
 * </ul>
 */
public class S3LockingService extends AbstractLeaseLockingService {

  private static final Logger log = LoggerFactory.getLogger(S3LockingService.class);

  public static final String DEFAULT_LOCKS_PREFIX = "locks/";
  public static final long DEFAULT_LEASE_DURATION_MS = 30_000L; // 30 seconds
  public static final long DEFAULT_POLL_INTERVAL_MS = 2_000L; // 2 seconds

  private final MinioClient minioClient;
  private final String bucket;
  private final String locksPrefix;

  /**
   * Basic constructor using default lock prefix ("locks/"), 30s lease duration, and 2s polling
   * interval.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param bucket Target S3 bucket name
   */
  public S3LockingService(MinioClient minioClient, String bucket) {
    this(
        minioClient,
        bucket,
        DEFAULT_LOCKS_PREFIX,
        DEFAULT_LEASE_DURATION_MS,
        DEFAULT_POLL_INTERVAL_MS);
  }

  /**
   * Full constructor allowing custom configuration for all locking parameters.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param bucket Target S3 bucket name
   * @param locksPrefix Object key prefix for locks and stop signals
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog poll interval for lock contention interrupt signals
   */
  public S3LockingService(
      MinioClient minioClient,
      String bucket,
      String locksPrefix,
      long leaseDurationMs,
      long pollIntervalMs) {
    this(
        minioClient,
        bucket,
        locksPrefix,
        leaseDurationMs,
        pollIntervalMs,
        new UuidUploadIdFactory());
  }

  /**
   * Full constructor allowing custom configuration including a custom {@link UploadIdFactory}.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param bucket Target S3 bucket name
   * @param locksPrefix Object key prefix for locks and stop signals
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog poll interval for lock contention interrupt signals
   * @param idFactory Custom {@link UploadIdFactory}
   */
  public S3LockingService(
      MinioClient minioClient,
      String bucket,
      String locksPrefix,
      long leaseDurationMs,
      long pollIntervalMs,
      UploadIdFactory idFactory) {
    super(idFactory, leaseDurationMs, pollIntervalMs, "s3-lock-shutdown-hook", "s3-lock-watchdog");
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.locksPrefix = sanitizePrefix(locksPrefix);
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    try {
      // List all object keys under locksPrefix in S3
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder().bucket(bucket).prefix(locksPrefix).build());

      for (Result<Item> result : results) {
        Item item = result.get();
        // Remove expired .lock lease objects
        if (item.objectName().endsWith(".lock") && isLockExpired(item.objectName())) {
          deleteObjectQuietly(item.objectName());
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to cleanup stale S3 locks", e);
    }
  }

  @Override
  protected UploadLock tryAcquireLock(UploadId uploadId, LeaseData leaseData) {
    if (leaseData == null) {
      return null;
    }
    String lockKey = buildLockKey(uploadId);
    String stopKey = buildStopKey(uploadId);

    if (!isLockExpired(lockKey)) {
      return null;
    }

    try {
      leaseData.setLockPath(lockKey);
      leaseData.setStopPath(stopKey);

      byte[] lockContentBytes = LeaseDataJsonSerializer.serializeToBytes(leaseData);

      // Layer 1: Conditional PutObject with "If-None-Match: *"
      // AWS S3 and compliant servers reject this with 412 Precondition Failed if the object already
      // exists
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(bucket)
              .object(lockKey)
              .extraHeaders(Collections.singletonMap("If-None-Match", "*"))
              .stream(
                  new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
              .build());

      // Layer 2: Jittered Read-After-Write Verification
      // For emulators or S3 backends where If-None-Match is not strictly enforced,
      // pause for a small randomized jitter (20-60ms) and verify our holderId is still the owner
      applyJitter();
      if (!verifyLockOwnership(lockKey, leaseData.getHolderId())) {
        return null;
      }

      return new S3UploadLock(leaseData, minioClient, bucket, lockKey, stopKey, activeInputStreams);
    } catch (ErrorResponseException e) {
      S3ErrorType errorType = S3Utils.parseErrorResponse(e);
      if (errorType == S3ErrorType.PRECONDITION_FAILED || errorType == S3ErrorType.CONFLICT) {
        log.info("Lock contention for key {}: S3 conditional write precondition failed", lockKey);
        return null;
      }
      log.warn("Unexpected S3 error response acquiring lock for key {}", lockKey, e);
      return null;
    } catch (Exception e) {
      log.warn("Unexpected error acquiring S3 lock for key {}", lockKey, e);
      return null;
    }
  }

  @Override
  protected boolean isLockExpired(UploadId uploadId) {
    if (uploadId == null) {
      return true;
    }
    return isLockExpired(buildLockKey(uploadId));
  }

  @Override
  protected boolean evictExpiredLock(UploadId uploadId) {
    if (uploadId == null) {
      return false;
    }
    String lockKey = buildLockKey(uploadId);
    return isLockExpired(lockKey);
  }

  @Override
  protected void writeStopSignal(UploadId uploadId) {
    String stopKey = buildStopKey(uploadId);
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(stopKey).stream(
                  new ByteArrayInputStream(new byte[0]), 0L, -1L)
              .build());
    } catch (Exception e) {
      log.warn("Failed to write lock stop signal object {} in bucket {}", stopKey, bucket, e);
    }
  }

  @Override
  protected void checkStopSignalForEntry(String uri, InputStream inputStream) {
    UploadId uploadId = idFactory.readUploadId(uri);
    if (uploadId == null) {
      return;
    }

    String stopKey = buildStopKey(uploadId);
    try {
      // Check if a .stop signal object was written by another pod requesting lock release
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(stopKey).build());
      // Remote stop signal object found! Interrupt local byte stream immediately
      Utils.interruptStream(inputStream);
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        // Normal state: no stop signal object in S3
        return;
      }
    } catch (Exception e) {
      log.debug("Error checking stop signal for {}", stopKey, e);
    }
  }

  boolean verifyLockOwnership(String lockKey, String expectedHolderId) {
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(lockKey).build())) {
      LeaseData remoteLock = LeaseDataJsonSerializer.deserialize(stream);
      return remoteLock != null
          && Strings.CS.equals(remoteLock.getHolderId(), expectedHolderId)
          && (remoteLock.getLockPath() == null
              || Strings.CS.equals(remoteLock.getLockPath(), lockKey));
    } catch (Exception e) {
      log.debug("Failed to verify lock ownership for key {}", lockKey, e);
      return false;
    }
  }

  private void deleteExpiredLockQuietly(String lockKey) {
    if (isLockExpired(lockKey)) {
      deleteObjectQuietly(lockKey);
    }
  }

  boolean isLockExpired(String lockKey) {
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(lockKey).build())) {

      LeaseData lock = LeaseDataJsonSerializer.deserialize(stream);
      if (lock == null) {
        return true;
      }
      return lock.getExpiresAt() < System.currentTimeMillis();
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return true; // Key missing -> Not locked
      }
      return true;
    } catch (Exception e) {
      log.debug("Failed to read lock object {}, treating as expired", lockKey, e);
      return true;
    }
  }

  private void deleteObjectQuietly(String key) {
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {}", key, e);
    }
  }

  void applyJitter() {
    applyJitter(20L, 60L);
  }

  private String sanitizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    String result = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    return result.endsWith("/") ? result : result + "/";
  }

  private String buildLockKey(UploadId uploadId) {
    return locksPrefix + uploadId.toString() + ".lock";
  }

  private String buildStopKey(UploadId uploadId) {
    return locksPrefix + uploadId.toString() + ".stop";
  }
}
