package me.desair.tus.server.upload;

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
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for distributed lease-based implementations of {@link UploadLockingService}.
 *
 * <p>Provides centralized concurrency coordination and lifecycle management including:
 *
 * <ul>
 *   <li>Template-based lock acquisition and TOCTOU-safe expired lock eviction.
 *   <li>Centralized lock status inspection.
 *   <li>JVM-local and cross-node/cross-pod lock contention signaling and watchdog polling.
 *   <li>Clean background daemon executor shutdown upon service close.
 * </ul>
 */
public abstract class AbstractLeaseLockingService extends AbstractCloseableResourceService
    implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(AbstractLeaseLockingService.class);

  protected final long leaseDurationMs;
  protected final long pollIntervalMs;
  protected UploadIdFactory idFactory;

  protected final Map<String, InputStream> activeInputStreams = new ConcurrentHashMap<>();
  protected final ScheduledExecutorService watchdogExecutor;

  /**
   * Constructs an {@link AbstractLeaseLockingService} and initializes the background contention
   * watchdog daemon.
   *
   * @param idFactory The factory used to parse and generate upload identifiers
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog polling interval in milliseconds
   * @param shutdownHookName Name of the JVM shutdown hook thread, or null if none
   * @param watchdogThreadName Name prefix for the background contention watchdog thread
   */
  protected AbstractLeaseLockingService(
      UploadIdFactory idFactory,
      long leaseDurationMs,
      long pollIntervalMs,
      String shutdownHookName,
      String watchdogThreadName) {
    super(shutdownHookName);
    this.idFactory = Objects.requireNonNull(idFactory, "The idFactory cannot be null");
    this.leaseDurationMs = leaseDurationMs;
    this.pollIntervalMs = pollIntervalMs;

    this.watchdogExecutor =
        Utils.scheduleWatchdog(
            watchdogThreadName,
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

    String holderId = UUID.randomUUID().toString();
    UploadLock lock = acquireOrEvictExpiredLock(uploadId, holderId, requestUri);
    if (lock == null) {
      throw new UploadAlreadyLockedException(
          "Upload with URI " + requestUri + " is currently locked");
    }
    return lock;
  }

  @Override
  public boolean isLocked(UploadId id) {
    if (id == null) {
      return false;
    }
    return !isLockExpired(id);
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    this.idFactory = Objects.requireNonNull(idFactory, "The idFactory cannot be null");
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

    // 1. Interrupt active local input stream in this JVM
    InputStream activeStream = activeInputStreams.remove(requestUri);
    if (activeStream != null) {
      Utils.interruptStream(activeStream);
    }

    // 2. Write a .stop signal to notify remote cluster replicas / pods
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId != null) {
      writeStopSignal(uploadId);
    }
  }

  @Override
  protected void cleanupOnClose() throws IOException {
    Utils.shutdownExecutor(watchdogExecutor);
    for (InputStream stream : activeInputStreams.values()) {
      Utils.interruptStream(stream);
    }
    activeInputStreams.clear();
    doCleanupOnClose();
  }

  /**
   * Subclass hook for additional cleanup upon service close. Default implementation does nothing.
   *
   * @throws IOException If closing fails
   */
  protected void doCleanupOnClose() throws IOException {}

  /**
   * Attempts lock acquisition or safe eviction of an expired lock with multi-layer TOCTOU
   * mitigations.
   *
   * @param uploadId The upload identifier
   * @param holderId The unique identifier of the lock contender
   * @param requestUri The target upload request URI
   * @return Acquired {@link UploadLock} handle, or null if lock is held by another active node
   * @throws TusException If a protocol error occurs
   * @throws IOException If an I/O error occurs
   */
  protected UploadLock acquireOrEvictExpiredLock(
      UploadId uploadId, String holderId, String requestUri) throws TusException, IOException {
    long now = System.currentTimeMillis();
    long expiresAt = now + leaseDurationMs;
    LeaseData leaseData =
        new LeaseData(holderId, requestUri, leaseDurationMs, expiresAt, now, null, null);

    UploadLock lock = tryAcquireLock(uploadId, leaseData);
    if (lock != null) {
      return lock;
    }

    // Lock acquisition encountered an existing lock. Inspect expiration status.
    if (isLockExpired(uploadId)) {
      // Lock is expired or abandoned: evict and retry acquisition
      boolean evicted = evictExpiredLock(uploadId);
      if (evicted) {
        now = System.currentTimeMillis();
        leaseData.setAcquiredAt(now);
        leaseData.setExpiresAt(now + leaseDurationMs);
        lock = tryAcquireLock(uploadId, leaseData);
        if (lock != null) {
          return lock;
        }
      }
    }
    return null;
  }

  /**
   * Subclass implementation of atomic primary lock acquisition.
   *
   * @param uploadId The upload identifier
   * @param leaseData The lease metadata describing the lock to acquire
   * @return Acquired {@link UploadLock}, or null if already held
   * @throws IOException If an I/O error occurs
   */
  protected abstract UploadLock tryAcquireLock(UploadId uploadId, LeaseData leaseData)
      throws IOException;

  /**
   * Subclass implementation determining if an upload lock is currently expired or abandoned.
   *
   * @param uploadId The upload identifier
   * @return true if the lock does not exist, has expired, or is abandoned; false if actively held
   */
  protected abstract boolean isLockExpired(UploadId uploadId);

  /**
   * Subclass implementation safely evicting an expired lock with TOCTOU safeguards.
   *
   * @param uploadId The upload identifier
   * @return true if successfully evicted, false if eviction lost a race or was aborted
   */
  protected abstract boolean evictExpiredLock(UploadId uploadId);

  /**
   * Writes a contention interrupt stop signal to the underlying storage mechanism.
   *
   * @param uploadId The upload identifier
   */
  protected abstract void writeStopSignal(UploadId uploadId);

  /**
   * Checks for a contention interrupt stop signal for an active input stream entry.
   *
   * @param uri The upload request URI
   * @param inputStream The active request input stream
   */
  protected abstract void checkStopSignalForEntry(String uri, InputStream inputStream);

  /** Polls active streams against stop signals across replicas. */
  public void checkStopSignals() {
    for (Map.Entry<String, InputStream> entry : activeInputStreams.entrySet()) {
      checkStopSignalForEntry(entry.getKey(), entry.getValue());
    }
  }

  /**
   * Applies randomized jitter backoff to settle concurrent in-flight writes.
   *
   * @param minMs Minimum jitter duration in milliseconds
   * @param maxMs Maximum jitter duration in milliseconds
   */
  protected void applyJitter(long minMs, long maxMs) {
    try {
      long jitterMs = minMs + (long) (Math.random() * (maxMs - minMs));
      Thread.sleep(jitterMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
