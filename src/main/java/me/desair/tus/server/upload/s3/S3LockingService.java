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
  private volatile boolean s3ConditionalWritesSupported = true;

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
        DEFAULT_POLL_INTERVAL_MS,
        new UuidUploadIdFactory());
  }

  /**
   * Constructor accepting explicit connection parameters without requiring a pre-existing
   * MinioClient.
   *
   * @param endpoint S3 endpoint URL (e.g. "https://s3.amazonaws.com" or "http://localhost:9000")
   * @param region S3 region name (e.g. "eu-central-1", "us-east-1")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket Target S3 bucket name
   */
  public S3LockingService(
      String endpoint, String region, String accessKey, String secretKey, String bucket) {
    this(
        buildMinioClient(endpoint, region, accessKey, secretKey),
        bucket,
        DEFAULT_LOCKS_PREFIX,
        DEFAULT_LEASE_DURATION_MS,
        DEFAULT_POLL_INTERVAL_MS,
        new UuidUploadIdFactory());
  }

  /**
   * Constructor accepting a pre-configured {@link MinioClient} along with explicit connection
   * parameters.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param endpoint S3 endpoint URL
   * @param region S3 region name
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket Target S3 bucket name
   */
  public S3LockingService(
      MinioClient minioClient,
      String endpoint,
      String region,
      String accessKey,
      String secretKey,
      String bucket) {
    this(
        minioClient != null
            ? minioClient
            : buildMinioClient(endpoint, region, accessKey, secretKey),
        bucket,
        DEFAULT_LOCKS_PREFIX,
        DEFAULT_LEASE_DURATION_MS,
        DEFAULT_POLL_INTERVAL_MS,
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

  /**
   * Configures custom jitter bounds used during lock acquisition read-after-write verification.
   *
   * <p>On S3-compatible backends lacking atomic conditional writes (e.g., Wasabi, SeaweedFS, Ceph,
   * Backblaze B2, older MinIO), randomized jitter backoff resolves last-write-wins races. For
   * high-latency or cross-region backends, configure higher bounds (e.g. 50–200 ms). For pure AWS
   * S3 or Cloudflare R2 deployments with strong conditional write enforcement, jitter can be
   * disabled by passing {@code 0, 0} to maximize throughput.
   *
   * @param minMs Minimum jitter duration in milliseconds (must be &gt;= 0)
   * @param maxMs Maximum jitter duration in milliseconds (must be &gt;= minMs)
   * @return This service instance for fluent chaining
   */
  public S3LockingService withJitter(long minMs, long maxMs) {
    setJitter(minMs, maxMs);
    return this;
  }

  /**
   * Configures whether the underlying S3 endpoint supports atomic conditional writes via {@code
   * If-None-Match: *}.
   *
   * <p>Defaults to {@code true} (optimistic). If the endpoint returns HTTP 501 Not Implemented,
   * this is automatically downgraded to {@code false}. For endpoints known to silently ignore
   * {@code If-None-Match: *} (such as Wasabi), setting this to {@code false} ensures the service
   * pre-checks existing lock status before writing.
   *
   * @param supported Whether conditional writes are supported
   * @return This service instance for fluent chaining
   */
  public S3LockingService withS3ConditionalWritesSupported(boolean supported) {
    this.s3ConditionalWritesSupported = supported;
    return this;
  }

  /**
   * Returns whether the underlying S3 endpoint currently supports atomic conditional writes.
   *
   * @return true if conditional writes are supported or assumed supported; false if downgraded or
   *     disabled
   */
  public boolean isS3ConditionalWritesSupported() {
    return s3ConditionalWritesSupported;
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

    // Optimistic conditional write:
    // Skip redundant isLockExpired() pre-check. In >99.9% of requests, no lock exists,
    // so an initial isLockExpired() issues an expensive S3 GET that 404s (~320ms penalty).
    // By issuing putObject with "If-None-Match: *" directly, happy-path acquisition
    // takes only 1 network call. If a lock already exists, S3 atomically returns 412
    // Precondition Failed, causing this method to return null. The caller
    // (acquireOrEvictExpiredLock) will then inspect isLockExpired() and evict if expired.

    try {
      leaseData.setLockPath(lockKey);
      leaseData.setStopPath(stopKey);

      byte[] lockContentBytes = LeaseDataJsonSerializer.serializeToBytes(leaseData);

      if (s3ConditionalWritesSupported) {
        // Layer 1: Optimistic Conditional PutObject with "If-None-Match: *"
        // AWS S3 and compliant servers reject this with 412 Precondition Failed if the object
        // already
        // exists
        try {
          minioClient.putObject(
              PutObjectArgs.builder()
                  .bucket(bucket)
                  .object(lockKey)
                  .extraHeaders(Collections.singletonMap("If-None-Match", "*"))
                  .stream(
                      new ByteArrayInputStream(lockContentBytes),
                      (long) lockContentBytes.length,
                      -1L)
                  .build());
        } catch (ErrorResponseException e) {
          S3ErrorType errorType = S3Utils.parseErrorResponse(e);
          if (errorType == S3ErrorType.API_NOT_IMPLEMENTED) {
            // Backend (e.g. Backblaze B2, Ceph RGW) does not support conditional writes.
            // Downgrade to non-CAS arbitration mode and proceed with safe pre-check + unconditional
            // write.
            log.info(
                "S3 endpoint does not support conditional writes (If-None-Match: *). "
                    + "Downgrading to non-CAS lock arbitration for key {}",
                lockKey);
            s3ConditionalWritesSupported = false;
            if (!isLockExpired(lockKey)) {
              return null;
            }
            writeUnconditionalLockObject(lockKey, lockContentBytes);
          } else {
            throw e;
          }
        }
      } else {
        // Non-CAS mode (e.g., Wasabi, Ceph RGW, Backblaze B2):
        // Verify no active unexpired lock exists before performing unconditional write
        if (!isLockExpired(lockKey)) {
          return null;
        }
        writeUnconditionalLockObject(lockKey, lockContentBytes);
      }

      // Layer 2: Jittered Read-After-Write Verification
      // For non-CAS backends or backends where If-None-Match is not strictly enforced,
      // pause for a randomized jitter duration to allow competing writes to settle,
      // then verify our holderId is still the owner
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

  private void writeUnconditionalLockObject(String lockKey, byte[] lockContentBytes)
      throws Exception {
    minioClient.putObject(
        PutObjectArgs.builder().bucket(bucket).object(lockKey).stream(
                new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
            .build());
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
    if (!isLockExpired(lockKey)) {
      return false;
    }
    // Delete the expired .lock object from S3 so the subsequent tryAcquireLock() conditional
    // write with "If-None-Match: *" can succeed and take over the abandoned lock.
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(lockKey).build());
      log.info("Evicted expired S3 lock for key {}", lockKey);
      return true;
    } catch (Exception e) {
      log.warn("Failed to evict expired S3 lock for key {}", lockKey, e);
      return false;
    }
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
      // Clean up the stream registration and delete the stop signal to avoid duplicate interrupts
      activeInputStreams.remove(uri);
      deleteObjectQuietly(stopKey);
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

  boolean isLockExpired(String lockKey) {
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(lockKey).build())) {

      LeaseData lock = LeaseDataJsonSerializer.deserialize(stream);
      // Add a 2000ms safety buffer beyond expiration time to absorb NTP clock
      // drift between distributed pods before considering a lease expired.
      return isLeaseExpired(lock, System.currentTimeMillis());
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

  @Override
  protected void applyJitter() {
    super.applyJitter();
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

  private static MinioClient buildMinioClient(
      String endpoint, String region, String accessKey, String secretKey) {
    String effectiveRegion = (region != null && !region.isEmpty()) ? region : "eu-central-1";
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .region(effectiveRegion)
        .build();
  }
}
