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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.AbstractCloseableResourceService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.S3UploadLockJsonSerializer;
import me.desair.tus.server.util.Utils;
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
public class S3LockingService extends AbstractCloseableResourceService
    implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(S3LockingService.class);

  public static final String DEFAULT_LOCKS_PREFIX = "locks/";
  public static final long DEFAULT_LEASE_DURATION_MS = 30_000L; // 30 seconds
  public static final long DEFAULT_POLL_INTERVAL_MS = 2_000L; // 2 seconds

  private final MinioClient minioClient;
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
    super("s3-lock-shutdown-hook");
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.locksPrefix = sanitizePrefix(locksPrefix);
    this.leaseDurationMs = leaseDurationMs;
    this.pollIntervalMs = pollIntervalMs;
    this.idFactory = Objects.requireNonNull(idFactory, "idFactory must not be null");

    // Background watchdog thread to poll S3 for .stop contention signals across pods
    this.watchdogExecutor =
        Utils.scheduleWatchdog(
            "s3-lock-watchdog",
            this::checkStopSignals,
            pollIntervalMs,
            pollIntervalMs,
            TimeUnit.MILLISECONDS);
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

    // Attempt lock acquisition or clear expired lock
    boolean acquired = acquireOrEvictExpiredLock(lockKey, holderId, requestUri, stopKey);
    if (!acquired) {
      throw new UploadAlreadyLockedException("Upload " + uploadId + " is currently locked");
    }

    // Create and return S3UploadLock instance with heartbeat lease auto-renewal
    return new S3UploadLock(
        minioClient,
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
    InputStream activeStream = activeInputStreams.remove(requestUri);
    if (activeStream != null) {
      Utils.interruptStream(activeStream);
    }

    // Step 2: Write a .stop signal object to S3 to signal lock contention across remote nodes/pods
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId != null) {
      writeStopSignal(uploadId);
    }
  }

  // HELPER METHODS & LOCK MANAGEMENT LOGIC

  private boolean acquireOrEvictExpiredLock(
      String lockKey, String holderId, String requestUri, String stopKey) {
    boolean acquired = attemptLockAcquisition(lockKey, holderId, requestUri, stopKey);
    if (!acquired && isLockExpired(lockKey)) {
      deleteObjectQuietly(lockKey);
      acquired = attemptLockAcquisition(lockKey, holderId, requestUri, stopKey);
    }
    return acquired;
  }

  private boolean attemptLockAcquisition(
      String lockKey, String holderId, String requestUri, String stopKey) {
    if (!isLockExpired(lockKey)) {
      return false;
    }

    long expiresAt = System.currentTimeMillis() + leaseDurationMs;
    try {
      S3UploadLock lock =
          new S3UploadLock(
              holderId, requestUri, bucket, lockKey, stopKey, leaseDurationMs, expiresAt);
      byte[] lockContentBytes = S3UploadLockJsonSerializer.serializeToBytes(lock);

      // Put lock lease object to S3
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(lockKey).stream(
                  new ByteArrayInputStream(lockContentBytes), (long) lockContentBytes.length, -1L)
              .build());
      return true;
    } catch (Exception e) {
      log.warn("Unexpected error acquiring S3 lock for key {}", lockKey, e);
      return false;
    }
  }

  private boolean isLockExpired(String lockKey) {
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(lockKey).build())) {

      S3UploadLock lock = S3UploadLockJsonSerializer.deserialize(stream);
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

  private void writeStopSignal(UploadId uploadId) {
    String stopKey = buildStopKey(uploadId);
    try {
      byte[] empty = new byte[0];
      // Write empty .stop signal object to S3
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(stopKey).stream(
                  new ByteArrayInputStream(empty), 0L, -1L)
              .build());
    } catch (Exception e) {
      log.debug("Failed to write lock stop signal to S3 key {}", stopKey, e);
    }
  }

  @Override
  protected void cleanupOnClose() throws IOException {
    Utils.shutdownExecutor(watchdogExecutor);
    activeInputStreams.clear();
  }

  private void checkStopSignals() {
    for (Map.Entry<String, InputStream> entry : activeInputStreams.entrySet()) {
      checkStopSignalForEntry(entry.getKey(), entry.getValue());
    }
  }

  private void checkStopSignalForEntry(String uri, InputStream inputStream) {
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

  private void deleteObjectQuietly(String key) {
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {}", key, e);
    }
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
