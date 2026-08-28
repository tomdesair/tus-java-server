package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import me.desair.tus.server.upload.AbstractLeaseLockingService;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed, NFS- and SMB-safe implementation of {@link UploadLockingService} using atomic
 * sibling mutex directories ({@link LeaseFileMutex}) and TTL-based JSON lease files.
 *
 * <p><b>Key Architectural Features & Distributed Concurrency Guide:</b>
 *
 * <ul>
 *   <li><b>Atomic Sibling Mutex Directory ({@code <UploadId>.mutex/})</b>: All state-modifying
 *       operations (lock acquisition, expired lease takeover, release, and cleanup) acquire a
 *       {@link LeaseFileMutex} before interacting with lock content. Atomic {@code mkdir} is
 *       guaranteed across POSIX filesystems, Windows SMB, and NFS (v3/v4 / AWS EFS).
 *   <li><b>In-Place Expired Lock Takeover (Zero Directory Moves)</b>: When an expired lease is
 *       encountered, the winning contender acquires the {@link LeaseFileMutex}, updates {@code
 *       lease.json} directly inside {@code <UploadId>.lock/}, and releases the mutex. The canonical
 *       lock directory never moves or disappears from disk, eliminating Time-of-Check to
 *       Time-of-Use (TOCTOU) race windows.
 *   <li><b>5-Second Crash Recovery</b>: If an ungracefully crashed node leaves {@code
 *       <UploadId>.mutex/} behind, subsequent contenders detect {@code now - mtime >= 5000ms},
 *       remove the abandoned mutex directory, and safely retry acquisition.
 *   <li><b>Deterministic TTL Leases & Heartbeat Renewal</b>: Lock directories contain {@code
 *       lease.json} with an absolute expiration timestamp. Active locks run a background daemon
 *       thread renewing {@code expiresAt} every {@code leaseDuration / 3} (default 10s for 30s
 *       lease).
 *   <li><b>Ownership Verification & Fencing</b>: When releasing or renewing a lock, the service
 *       verifies that {@code holderId} in {@code lease.json} still matches the current holder,
 *       preventing an expired/unpaused node from corrupting a successor's active lease.
 *   <li><b>Cross-Replica Lock Contention & .stop Signals</b>: When a concurrent request arrives for
 *       a locked upload (e.g. HEAD or DELETE while PATCH is streaming), the service writes {@code
 *       <UploadId>.stop}. A background watchdog polls every 1.5 seconds and interrupts the stream.
 * </ul>
 */
public class LeaseFileLockingService extends AbstractLeaseLockingService {

  private static final Logger log = LoggerFactory.getLogger(LeaseFileLockingService.class);

  public static final String DEFAULT_LOCKS_DIRECTORY = "locks";
  public static final long DEFAULT_LEASE_DURATION_MS = 30_000L; // 30 seconds
  public static final long DEFAULT_POLL_INTERVAL_MS = 1_500L; // 1.5 seconds
  public static final long EMPTY_DIR_GRACE_PERIOD_MS = 5_000L; // 5 seconds grace window

  private final Path storagePath;

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
    super(
        idFactory,
        leaseDurationMs,
        pollIntervalMs,
        "lease-file-lock-shutdown-hook",
        "lease-file-lock-watchdog");
    Validate.notBlank(storagePath, "The storage path cannot be blank");
    this.storagePath = Paths.get(storagePath, DEFAULT_LOCKS_DIRECTORY);
    initStoragePath();
  }

  public Path getStoragePath() {
    return storagePath;
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    if (!Files.exists(storagePath) || !Files.isDirectory(storagePath)) {
      return;
    }

    try (DirectoryStream<Path> stream = Files.newDirectoryStream(storagePath)) {
      long now = System.currentTimeMillis();
      for (Path path : stream) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".lock") && Files.isDirectory(path)) {
          if (isLockDirectoryExpired(path, now)) {
            atomicEvictExpiredLock(path);
          }
        } else if (fileName.endsWith(".mutex") && Files.isDirectory(path)) {
          try {
            FileTime mtime = Files.getLastModifiedTime(path);
            if (now - mtime.toMillis() > 10_000L) {
              FileUtils.deleteDirectory(path.toFile());
            }
          } catch (IOException ignored) {
            // Ignore transient cleanup error
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

  /**
   * Attempts primary lock acquisition or in-place takeover of an expired lease under the protection
   * of a {@link LeaseFileMutex}.
   *
   * <p><b>Detailed Concurrency Workflow:</b>
   *
   * <ol>
   *   <li><b>Acquire Sibling Mutex Directory ({@code <UploadId>.mutex/})</b>: Acquires {@link
   *       LeaseFileMutex}. Because directory creation maps to atomic {@code mkdir} at the
   *       OS/filesystem layer, exactly one thread or process across the cluster succeeds. If the
   *       mutex already exists and is &lt; 5s old, contention is in progress and we return {@code
   *       null}. If &gt;= 5s old, the previous holder crashed, so {@link LeaseFileMutex} removes
   *       the stale mutex and retries.
   *   <li><b>Inspect Existing Lease Under Mutex</b>: With exclusive access guaranteed, we read
   *       {@code <UploadId>.lock/lease.json}. If it exists and {@code !isExpired(now)}, another
   *       node holds an active unexpired lock; we abort and return {@code null}.
   *   <li><b>In-Place Acquisition / Takeover</b>: If the lock directory does not exist, we create
   *       it. If {@code lease.json} does not exist or is expired, we update {@code leaseData} and
   *       write the new {@code lease.json} atomically via a temporary file rename. The lock
   *       directory never disappears from disk, eliminating TOCTOU race windows.
   *   <li><b>Release Sibling Mutex</b>: When exiting the try-with-resources block, {@code
   *       <UploadId>.mutex/} is automatically deleted, allowing future contenders to inspect or
   *       acquire.
   * </ol>
   *
   * @param uploadId The upload identifier
   * @param leaseData The lease metadata describing the lock to acquire
   * @return Acquired {@link UploadLock} handle, or null if locked by another active process
   * @throws IOException If an I/O error occurs
   */
  @Override
  protected UploadLock tryAcquireLock(UploadId uploadId, LeaseData leaseData) throws IOException {
    if (uploadId == null || leaseData == null) {
      return null;
    }

    Path lockDirPath = getLockDirPath(uploadId);
    Path stopFilePath = getStopFilePath(uploadId);

    if (lockDirPath == null) {
      return null;
    }

    // Step 1: Acquire sibling mutex (<UploadId>.mutex) directly in try-with-resources
    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDirPath)) {
      if (!mutex.isAcquired()) {
        return null;
      }

      long now = System.currentTimeMillis();
      Path leaseFile = lockDirPath.resolve("lease.json");

      // Step 2: Under exclusive mutex, inspect existing lease
      if (Files.exists(lockDirPath)) {
        if (Files.exists(leaseFile)) {
          try {
            LeaseData existingLease = LeaseDataJsonSerializer.deserialize(leaseFile);
            if (existingLease != null && !existingLease.isExpired(now)) {
              // Active unexpired lease held by another live node
              return null;
            }
          } catch (Exception e) {
            // Corrupted lease: check directory grace period
            FileTime mtime = Files.getLastModifiedTime(lockDirPath);
            if (now - mtime.toMillis() < EMPTY_DIR_GRACE_PERIOD_MS) {
              return null;
            }
          }
        } else {
          // Empty directory: check directory grace period
          FileTime mtime = Files.getLastModifiedTime(lockDirPath);
          if (now - mtime.toMillis() < EMPTY_DIR_GRACE_PERIOD_MS) {
            return null;
          }
        }
      } else {
        Utils.ensureDirectoryExists(lockDirPath);
      }

      // Step 3: Fresh acquisition or in-place takeover of expired/abandoned lease
      leaseData.setLockPath(lockDirPath.toString());
      leaseData.setStopPath(stopFilePath != null ? stopFilePath.toString() : null);

      // Write lease metadata via a temporary file and atomically rename it into place.
      // Why the temporary file move is critical:
      // Status checks (e.g. isLocked()) perform fast read-only queries on lease.json without
      // acquiring the write mutex. Writing directly to lease.json would expose a 0-byte or
      // partially written JSON file to concurrent readers during stream flushing.
      // An atomic move (rename) guarantees lease.json appears on disk 100% complete and valid.
      Path tmpLeaseFile = lockDirPath.resolve("lease.json.tmp." + UUID.randomUUID());
      LeaseDataJsonSerializer.serializeToPath(leaseData, tmpLeaseFile);
      Files.move(
          tmpLeaseFile,
          leaseFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);

      // Clear any lingering stop signal file from prior contention
      if (stopFilePath != null) {
        try {
          Files.deleteIfExists(stopFilePath);
        } catch (IOException ignored) {
          // Safe to ignore
        }
      }

      return new LeaseFileUploadLock(leaseData, lockDirPath, stopFilePath, activeInputStreams);
    } catch (Exception e) {
      log.debug("Error acquiring lease for upload {}", uploadId, e);
      return null;
    }
  }

  @Override
  protected boolean isLockExpired(UploadId uploadId) {
    if (uploadId == null) {
      return true;
    }
    Path lockDirPath = getLockDirPath(uploadId);
    return isLockDirectoryExpired(lockDirPath, System.currentTimeMillis());
  }

  @Override
  protected boolean evictExpiredLock(UploadId uploadId) {
    if (uploadId == null) {
      return false;
    }
    Path lockDirPath = getLockDirPath(uploadId);
    return atomicEvictExpiredLock(lockDirPath);
  }

  @Override
  protected void writeStopSignal(UploadId uploadId) {
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

  @Override
  protected void checkStopSignalForEntry(String uri, InputStream inputStream) {
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
  boolean isLockDirectoryExpired(Path lockDirPath, long now) {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return true;
    }

    Path leaseFile = lockDirPath.resolve("lease.json");
    if (Files.exists(leaseFile)) {
      try {
        LeaseData lease = LeaseDataJsonSerializer.deserialize(leaseFile);
        if (lease != null) {
          return lease.isExpired(now);
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
   * Safely evicts an expired lock directory under sibling mutex protection.
   *
   * @param lockDirPath Path to the lock directory to evict
   * @return {@code true} if the expired directory was successfully evicted; {@code false} if the
   *     lock is actively held or mutex could not be acquired
   */
  boolean atomicEvictExpiredLock(Path lockDirPath) {
    if (lockDirPath == null || !Files.exists(lockDirPath)) {
      return false;
    }
    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDirPath)) {
      if (!mutex.isAcquired()) {
        return false;
      }
      long now = System.currentTimeMillis();
      if (!isLockDirectoryExpired(lockDirPath, now)) {
        return false;
      }
      FileUtils.deleteDirectory(lockDirPath.toFile());
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  Path getLockDirPath(UploadId id) {
    if (id == null) {
      return null;
    }
    return storagePath.resolve(id.toString() + ".lock");
  }

  Path getStopFilePath(UploadId id) {
    if (id == null) {
      return null;
    }
    return storagePath.resolve(id.toString() + ".stop");
  }

  private synchronized void initStoragePath() {
    try {
      Utils.ensureDirectoryExists(storagePath);
    } catch (IOException e) {
      String message =
          "Unable to create the directory specified by the storage path " + storagePath;
      log.error(message, e);
      throw new StoragePathNotAvailableException(message, e);
    }
  }
}
