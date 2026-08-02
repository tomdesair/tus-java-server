package me.desair.tus.server.upload.s3;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Distributed S3-backed implementation of {@link UploadLockingService}.
 *
 * <p>Key Architecture Features:
 *
 * <ul>
 *   <li><b>Distributed Conditional Locking</b>: Uses S3 conditional writes ({@code If-None-Match:
 *       "*"}) to atomically acquire locks across multi-replica application pods without requiring
 *       external storage like Redis.
 *   <li><b>Heartbeat & Lease Auto-Renewal</b>: Managed locks spawn background daemon threads to
 *       auto-renew lease TTLs.
 *   <li><b>Cross-Pod Lock Contention Resolution</b>: Supports concurrent request cancellation (e.g.
 *       HEAD/DELETE during PATCH) by writing {@code .stop} signal files in S3 and periodically
 *       inspecting them with a watchdog poller thread.
 * </ul>
 */
public class S3LockingService implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(S3LockingService.class);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  public static final String DEFAULT_LOCKS_PREFIX = "locks/";
  public static final long DEFAULT_LEASE_DURATION_MS = 30_000L; // 30 seconds
  public static final long DEFAULT_POLL_INTERVAL_MS = 2_000L; // 2 seconds

  private final S3Client s3Client;
  private final String bucket;
  private final String locksPrefix;
  private final long leaseDurationMs;
  private final long pollIntervalMs;

  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private final Map<String, InputStream> activeInputStreams = new ConcurrentHashMap<>();
  private final ScheduledExecutorService watchdogExecutor;

  /**
   * Basic constructor using default lock prefix ("locks/"), 30s lease duration, and 2s polling
   * interval.
   *
   * @param s3Client Pre-configured AWS SDK v2 S3 client
   * @param bucket Target S3 bucket name
   */
  public S3LockingService(S3Client s3Client, String bucket) {
    this(
        s3Client,
        bucket,
        DEFAULT_LOCKS_PREFIX,
        DEFAULT_LEASE_DURATION_MS,
        DEFAULT_POLL_INTERVAL_MS);
  }

  /**
   * Full constructor allowing custom configuration for all locking parameters.
   *
   * @param s3Client Pre-configured AWS SDK v2 S3 client
   * @param bucket Target S3 bucket name
   * @param locksPrefix Object key prefix for locks and stop signals
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog poll interval for lock contention interrupt signals
   */
  public S3LockingService(
      S3Client s3Client,
      String bucket,
      String locksPrefix,
      long leaseDurationMs,
      long pollIntervalMs) {
    this.s3Client = Objects.requireNonNull(s3Client, "S3Client must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.locksPrefix = sanitizePrefix(locksPrefix);
    this.leaseDurationMs = leaseDurationMs;
    this.pollIntervalMs = pollIntervalMs;

    this.watchdogExecutor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "s3-lock-watchdog");
              t.setDaemon(true);
              return t;
            });

    if (pollIntervalMs > 0) {
      this.watchdogExecutor.scheduleAtFixedRate(
          this::checkStopSignals, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId == null) {
      return null;
    }

    String lockKey = buildLockKey(uploadId);
    String stopKey = buildStopKey(uploadId);
    String holderId = UUID.randomUUID().toString();

    // High-level locking strategy: attempt acquisition, resolve expired lock if necessary, or throw
    // exception
    boolean acquired = acquireOrEvictExpiredLock(lockKey, holderId);
    if (!acquired) {
      throw new UploadAlreadyLockedException("Upload " + uploadId + " is currently locked");
    }

    return new S3UploadLock(
        s3Client,
        bucket,
        lockKey,
        stopKey,
        holderId,
        leaseDurationMs,
        requestUri,
        activeInputStreams);
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    try {
      ListObjectsV2Response listResponse =
          s3Client.listObjectsV2(
              ListObjectsV2Request.builder().bucket(bucket).prefix(locksPrefix).build());

      for (S3Object s3Object : listResponse.contents()) {
        if (s3Object.key().endsWith(".lock") && isLockExpired(s3Object.key())) {
          deleteObjectQuietly(s3Object.key());
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to cleanup stale S3 locks", e);
    }
  }

  @Override
  public boolean isLocked(UploadId id) {
    if (id == null) {
      return false;
    }
    String lockKey = buildLockKey(id);
    return !isLockExpired(lockKey);
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    if (idFactory != null) {
      this.idFactory = idFactory;
    }
  }

  @Override
  public void registerInputStream(String requestUri, InputStream inputStream) {
    if (requestUri != null && inputStream != null) {
      activeInputStreams.put(requestUri, inputStream);
    }
  }

  @Override
  public void requestLockRelease(String requestUri) {
    if (requestUri == null) {
      return;
    }

    // Step 1: Interrupt local active payload byte stream if hosted on this node
    InputStream activeStream = activeInputStreams.get(requestUri);
    if (activeStream != null) {
      interruptStream(activeStream);
    }

    // Step 2: Write remote .stop signal object to S3 so other application pods can interrupt
    // ongoing streams
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId != null) {
      writeStopSignal(uploadId);
    }
  }

  // HELPER METHODS (Single Level of Abstraction)

  /** Attempts atomic lock acquisition; if failed due to expiration, evicts old lock and retries. */
  private boolean acquireOrEvictExpiredLock(String lockKey, String holderId) {
    boolean acquired = attemptLockAcquisition(lockKey, holderId);
    if (!acquired && isLockExpired(lockKey)) {
      deleteObjectQuietly(lockKey);
      acquired = attemptLockAcquisition(lockKey, holderId);
    }
    return acquired;
  }

  /** Performs S3 conditional write (If-None-Match: "*") to atomically acquire lock object. */
  private boolean attemptLockAcquisition(String lockKey, String holderId) {
    if (!isLockExpired(lockKey)) {
      return false;
    }

    try {
      long expiresAt = System.currentTimeMillis() + leaseDurationMs;
      String lockContent =
          String.format(
              "{\"holder\":\"%s\",\"expiresAt\":%d,\"acquiredAt\":%d}",
              holderId, expiresAt, System.currentTimeMillis());

      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(lockKey).ifNoneMatch("*").build(),
          RequestBody.fromString(lockContent, StandardCharsets.UTF_8));
      return true;
    } catch (S3Exception e) {
      // 412 Precondition Failed, 409 Conflict, or 400 Bad Request indicates lock already held by
      // another pod
      if (isPreconditionFailedStatus(e)) {
        return false;
      }
      log.warn("S3 conditional put failed for lock key {}", lockKey, e);
      return false;
    } catch (Exception e) {
      log.warn("Unexpected error acquiring S3 lock for key {}", lockKey, e);
      return false;
    }
  }

  /** Evaluates whether an S3 exception status indicates a conditional write conflict. */
  private boolean isPreconditionFailedStatus(S3Exception e) {
    return e.statusCode() == 412
        || e.statusCode() == 409
        || e.statusCode() == 400
        || (e.awsErrorDetails() != null
            && "PreconditionFailed".equalsIgnoreCase(e.awsErrorDetails().errorCode()));
  }

  /** Reads lock object JSON from S3 and checks if the lease expiration timestamp has passed. */
  private boolean isLockExpired(String lockKey) {
    try (ResponseInputStream<GetObjectResponse> stream =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(lockKey).build())) {

      LockData lockData = OBJECT_MAPPER.readValue(stream, LockData.class);
      return lockData.expiresAt < System.currentTimeMillis();
    } catch (NoSuchKeyException e) {
      return true; // No lock object means not locked (expired)
    } catch (Exception e) {
      log.debug("Failed to read lock object {}, treating as expired", lockKey, e);
      return true;
    }
  }

  /** Writes a .stop signal object to S3 to signal lock contention to remote pods. */
  private void writeStopSignal(UploadId uploadId) {
    String stopKey = buildStopKey(uploadId);
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(stopKey).build(), RequestBody.empty());
    } catch (Exception e) {
      log.debug("Failed to write lock stop signal to S3 key {}", stopKey, e);
    }
  }

  /** Watchdog thread callback inspecting active local streams for remote .stop signals. */
  private void checkStopSignals() {
    for (Map.Entry<String, InputStream> entry : activeInputStreams.entrySet()) {
      checkStopSignalForEntry(entry.getKey(), entry.getValue());
    }
  }

  /** Inspects whether an S3 .stop signal object exists for a specific active upload URI. */
  private void checkStopSignalForEntry(String uri, InputStream inputStream) {
    UploadId uploadId = idFactory.readUploadId(uri);
    if (uploadId == null) {
      return;
    }

    String stopKey = buildStopKey(uploadId);
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(stopKey).build());
      // Remote stop signal object found! Interrupt local byte stream immediately
      interruptStream(inputStream);
    } catch (NoSuchKeyException ignored) {
      // Normal state: no stop signal
    } catch (Exception e) {
      log.debug("Error checking stop signal for {}", stopKey, e);
    }
  }

  /** Interrupts active payload stream cleanly using InterruptibleInputStream or fallback close. */
  private void interruptStream(InputStream is) {
    if (is instanceof InterruptibleInputStream) {
      ((InterruptibleInputStream) is).interrupt();
    } else {
      try {
        is.close();
      } catch (Exception ignored) {
        // Stream close failure ignored defensively
      }
    }
  }

  /** Deletes an object quietly from S3 without throwing exceptions. */
  private void deleteObjectQuietly(String key) {
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {}", key, e);
    }
  }

  /** Ensures key prefixes are relative and end with a trailing slash. */
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

  /** Internal JSON data model for S3 lock lease metadata. */
  private static class LockData {
    public String holder;
    public long expiresAt;
    public long acquiredAt;
  }
}
