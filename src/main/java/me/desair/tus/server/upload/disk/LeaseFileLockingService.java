package me.desair.tus.server.upload.disk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed, NFS- and SMB-safe implementation of {@link UploadLockingService} using atomic
 * directory creation and TTL-based JSON lease files.
 *
 * <p><b>Key Architectural Features & Distributed Concurrency Guide:</b>
 *
 * <ul>
 *   <li><b>Cross-Platform Directory Atomicity</b>: Lock acquisition uses {@code
 *       Files.createDirectory()} on {@code <storagePath>/locks/<UploadId>.lock/}. Directory
 *       creation is atomic across local Linux/Windows filesystems, POSIX NFS (v3/v4 {@code mkdir}
 *       RPC), and Windows SMB/CIFS shares ({@code CreateDirectoryW}), eliminating lock daemon
 *       failures and container namespace isolation issues.
 *   <li><b>Deterministic TTL Leases & Crash Recovery</b>: Each lock directory contains a small JSON
 *       lease file ({@code lease.json}) with an absolute expiration timestamp. If a node crashes
 *       ungracefully ({@code kill -9}, OOM), the lease auto-expires within the TTL (default 30s),
 *       allowing peer cluster nodes to evict and re-acquire the lock without admin intervention.
 *   <li><b>Heartbeat Lease Auto-Renewal</b>: Active locks run a background daemon thread that
 *       periodically updates {@code expiresAt} in {@code lease.json} every {@code leaseDuration /
 *       3} (e.g. every 10s for a 30s lease), preventing lock expiration during long streaming
 *       uploads.
 *   <li><b>Atomic Stale Lock Eviction</b>: To prevent contender race collisions when multiple nodes
 *       detect an expired lock simultaneously, eviction renames the {@code .lock} directory
 *       atomically (using {@link StandardCopyOption#ATOMIC_MOVE}) to a unique {@code
 *       .evicting.<uuid>} directory before deleting its contents.
 *   <li><b>5-Second Directory Grace Period</b>: If a contender discovers an existing lock directory
 *       where {@code lease.json} is still being written or corrupted, it inspects the directory's
 *       creation time / last modified time. If the directory is newer than 5 seconds, it is treated
 *       as actively acquiring (throwing {@link UploadAlreadyLockedException}); if older, it is
 *       treated as abandoned and safely evicted.
 *   <li><b>Cross-Replica Lock Contention & .stop Signals</b>: When a concurrent request arrives for
 *       a locked upload (e.g. HEAD or DELETE while a PATCH is streaming), the service writes a
 *       {@code <storagePath>/locks/<UploadId>.stop} signal file. A background watchdog thread polls
 *       every 1.5 seconds and interrupts the active input stream immediately, allowing the resuming
 *       request to proceed without false lock conflicts.
 * </ul>
 */
public class LeaseFileLockingService extends AbstractDiskBasedService
    implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(LeaseFileLockingService.class);

  public static final String DEFAULT_LOCKS_DIRECTORY = "locks";
  public static final long DEFAULT_LEASE_DURATION_MS = 30_000L; // 30 seconds
  public static final long DEFAULT_POLL_INTERVAL_MS = 1_500L; // 1.5 seconds
  public static final long EMPTY_DIR_GRACE_PERIOD_MS = 5_000L; // 5 seconds grace window

  private final long leaseDurationMs;
  private final long pollIntervalMs;
  private UploadIdFactory idFactory;

  private final Map<String, InputStream> activeInputStreams = new ConcurrentHashMap<>();
  private final ScheduledExecutorService watchdogExecutor;

  /**
   * Constructs a LeaseFileLockingService with default 30s lease duration, 1.5s watchdog poll
   * interval, and {@link UuidUploadIdFactory}.
   *
   * @param storagePath The base storage directory path
   */
  public LeaseFileLockingService(String storagePath) {
    this(
        new UuidUploadIdFactory(),
        storagePath,
        DEFAULT_LEASE_DURATION_MS,
        DEFAULT_POLL_INTERVAL_MS);
  }

  /**
   * Constructs a LeaseFileLockingService with a custom {@link UploadIdFactory}.
   *
   * @param idFactory The custom UploadIdFactory
   * @param storagePath The base storage directory path
   */
  public LeaseFileLockingService(UploadIdFactory idFactory, String storagePath) {
    this(idFactory, storagePath, DEFAULT_LEASE_DURATION_MS, DEFAULT_POLL_INTERVAL_MS);
  }

  /**
   * Constructs a LeaseFileLockingService with custom lease duration and watchdog poll interval.
   *
   * @param storagePath The base storage directory path
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog poll interval in milliseconds
   */
  public LeaseFileLockingService(String storagePath, long leaseDurationMs, long pollIntervalMs) {
    this(new UuidUploadIdFactory(), storagePath, leaseDurationMs, pollIntervalMs);
  }

  /**
   * Full constructor allowing custom configuration of all locking parameters.
   *
   * @param idFactory The custom UploadIdFactory
   * @param storagePath The base storage directory path
   * @param leaseDurationMs Lock lease duration in milliseconds
   * @param pollIntervalMs Watchdog poll interval in milliseconds
   */
  public LeaseFileLockingService(
      UploadIdFactory idFactory, String storagePath, long leaseDurationMs, long pollIntervalMs) {
    super(storagePath + File.separator + DEFAULT_LOCKS_DIRECTORY, "lease-file-lock-shutdown-hook");
    this.idFactory = Objects.requireNonNull(idFactory, "The idFactory cannot be null");
    this.leaseDurationMs = leaseDurationMs;
    this.pollIntervalMs = pollIntervalMs;

    // Background watchdog thread to poll storage directory for .stop contention signals across pods
    this.watchdogExecutor =
        Utils.scheduleWatchdog(
            "lease-file-lock-watchdog",
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

    Path lockDirPath = getLockDirPath(uploadId);
    Path stopFilePath = getStopFilePath(uploadId);
    String holderId = UUID.randomUUID().toString();

    // Attempt lock acquisition, handling active locks, grace windows, and expired lock eviction
    return acquireOrEvictExpiredLock(lockDirPath, stopFilePath, holderId, requestUri, uploadId);
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    Path locksDir = getStoragePath();
    if (!Files.exists(locksDir) || !Files.isDirectory(locksDir)) {
      return;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(locksDir)) {
      long now = System.currentTimeMillis();
      for (Path path : stream) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".lock") && Files.isDirectory(path)) {
          if (isLockDirectoryExpired(path, now)) {
            atomicEvictExpiredLock(path);
          }
        } else if (fileName.endsWith(".stop") && Files.isRegularFile(path)) {
          try {
            FileTime mtime = Files.getLastModifiedTime(path);
            if (now - mtime.toMillis() > 10_000L) {
              Files.deleteIfExists(path);
            }
          } catch (IOException ignored) {
            // Ignore transient cleanup error
          }
        }
      }
    }
  }

  @Override
  public boolean isLocked(UploadId id) {
    if (id == null) {
      return false;
    }
    Path lockDirPath = getLockDirPath(id);
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return false;
    }
    return !isLockDirectoryExpired(lockDirPath, System.currentTimeMillis());
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

    // 2. Write a .stop signal file to storage to notify remote cluster replicas
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
  }

  // ===============================================================================================
  // INTERNAL LOCK ACQUISITION & EVICTION HELPERS
  // ===============================================================================================

  private UploadLock acquireOrEvictExpiredLock(
      Path lockDirPath, Path stopFilePath, String holderId, String requestUri, UploadId uploadId)
      throws TusException, IOException {

    UploadLock lock = tryAcquireLock(lockDirPath, stopFilePath, holderId, requestUri, uploadId);
    if (lock != null) {
      return lock;
    }

    // Lock acquisition encountered an existing lock directory. Inspect lease status.
    long now = System.currentTimeMillis();
    if (isLockDirectoryExpired(lockDirPath, now)) {
      // Lock is expired or abandoned: atomically evict and retry acquisition
      boolean evicted = atomicEvictExpiredLock(lockDirPath);
      if (evicted) {
        lock = tryAcquireLock(lockDirPath, stopFilePath, holderId, requestUri, uploadId);
        if (lock != null) {
          return lock;
        }
      }
    }

    // Lock is held by another active node or within the initial creation grace window
    throw new UploadAlreadyLockedException(
        "Upload with URI " + requestUri + " is currently locked");
  }

  private UploadLock tryAcquireLock(
      Path lockDirPath, Path stopFilePath, String holderId, String requestUri, UploadId uploadId)
      throws IOException {

    try {
      Utils.ensureDirectoryExists(lockDirPath.getParent());
      // Atomic directory creation on Linux, Windows, NFS, and SMB
      Files.createDirectory(lockDirPath);

      LeaseFileUploadLock lock =
          new LeaseFileUploadLock(
              lockDirPath, stopFilePath, holderId, leaseDurationMs, requestUri, activeInputStreams);

      // Write lease metadata JSON file inside the lock directory atomically
      Path leaseTmpFile = lockDirPath.resolve("lease.json.tmp." + UUID.randomUUID());
      Path leaseFile = lockDirPath.resolve("lease.json");
      Utils.writeJson(lock, leaseTmpFile, false);
      Files.move(
          leaseTmpFile,
          leaseFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);

      // Clear any lingering stop signal file from prior contention
      try {
        Files.deleteIfExists(stopFilePath);
      } catch (IOException ignored) {
        // Safe to ignore
      }

      return lock;
    } catch (FileAlreadyExistsException e) {
      // Lock directory already exists
      return null;
    } catch (Exception e) {
      log.debug("Error initializing lease for lock directory {}", lockDirPath, e);
      return null;
    }
  }

  /**
   * Determines if a lock directory is expired or abandoned.
   *
   * <p>Edge cases handled:
   *
   * <ul>
   *   <li>Valid {@code lease.json} exists: checks if {@code expiresAt < now}.
   *   <li>Missing or unparseable {@code lease.json}: checks if directory mtime is older than 5
   *       seconds (grace window for active writes).
   * </ul>
   */
  private boolean isLockDirectoryExpired(Path lockDirPath, long now) {
    if (!Files.exists(lockDirPath)) {
      return true;
    }

    Path leaseFile = lockDirPath.resolve("lease.json");
    if (Files.exists(leaseFile)) {
      try {
        LeaseFileUploadLock lease = Utils.readJson(leaseFile, LeaseFileUploadLock.class, false);
        if (lease != null) {
          return lease.getExpiresAt() < now;
        }
      } catch (Exception e) {
        log.debug("Failed to read lease file {}, checking grace period", leaseFile, e);
      }
    }

    // If lease.json is missing or unparseable, check directory mtime against grace period
    try {
      FileTime mtime = Files.getLastModifiedTime(lockDirPath);
      long dirAgeMs = now - mtime.toMillis();
      // If the directory was created within the last 5 seconds, another node is actively writing
      return dirAgeMs >= EMPTY_DIR_GRACE_PERIOD_MS;
    } catch (IOException e) {
      // If directory is inaccessible or changing, assume active write in progress
      return false;
    }
  }

  /**
   * Atomically evicts an expired or abandoned lock directory by renaming it to a unique temporary
   * directory before deletion, preventing contention races where multiple nodes try to evict the
   * same directory simultaneously.
   *
   * @param lockDirPath Path to the lock directory to evict
   * @return {@code true} if the expired directory was successfully evicted; {@code false} if
   *     another contender already evicted it or if the moved directory was an active lock
   */
  boolean atomicEvictExpiredLock(Path lockDirPath) {
    long now = System.currentTimeMillis();
    // Re-verify expiration immediately before rename to avoid moving a newly acquired active lock
    if (!isLockDirectoryExpired(lockDirPath, now)) {
      return false;
    }

    Path evictPath =
        lockDirPath.resolveSibling(lockDirPath.getFileName() + ".evicting." + UUID.randomUUID());
    try {
      // Atomic directory rename guarantees exactly one contender wins the right to delete
      Files.move(lockDirPath, evictPath, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      // Another contender already moved or removed the directory; safe to proceed
      return false;
    }

    // Delete files inside evicted directory, then delete directory itself
    try {
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(evictPath)) {
        for (Path file : stream) {
          Files.deleteIfExists(file);
        }
      }
      Files.deleteIfExists(evictPath);
    } catch (IOException ignored) {
    }
    return true;
  }

  void writeStopSignal(UploadId uploadId) {
    Path stopFilePath = getStopFilePath(uploadId);
    if (stopFilePath != null) {
      try {
        Utils.ensureDirectoryExists(stopFilePath.getParent());
        Files.write(stopFilePath, new byte[0]);
      } catch (IOException e) {
        log.warn("Failed to write lock stop signal file {}", stopFilePath, e);
      }
    }
  }

  void checkStopSignals() {
    for (Map.Entry<String, InputStream> entry : activeInputStreams.entrySet()) {
      checkStopSignalForEntry(entry.getKey(), entry.getValue());
    }
  }

  private void checkStopSignalForEntry(String uri, InputStream inputStream) {
    UploadId uploadId = idFactory.readUploadId(uri);
    if (uploadId == null) {
      return;
    }

    Path stopFilePath = getStopFilePath(uploadId);
    if (stopFilePath != null && Files.exists(stopFilePath)) {
      log.info("Watchdog detected stop file for upload ID {}. Interrupting stream.", uploadId);
      Utils.interruptStream(inputStream);
      activeInputStreams.remove(uri);
      try {
        Files.deleteIfExists(stopFilePath);
      } catch (IOException ignored) {
        // Safe to ignore
      }
    }
  }

  Path getLockDirPath(UploadId id) {
    if (id == null) {
      return null;
    }
    return getStoragePath().resolve(id.toString() + ".lock");
  }

  Path getStopFilePath(UploadId id) {
    if (id == null) {
      return null;
    }
    return getStoragePath().resolve(id.toString() + ".stop");
  }
}
