package me.desair.tus.server.upload.disk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import me.desair.tus.server.upload.AbstractLeaseLockingService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed, NFS- and SMB-safe implementation of {@link UploadLockingService} using atomic
 * directory staging, atomic renames, and TTL-based JSON lease files.
 *
 * <p><b>Key Architectural Features & Distributed Concurrency Guide:</b>
 *
 * <ul>
 *   <li><b>Atomic Directory Staging & Renames</b>: Lock acquisition stages new locks in a temporary
 *       sibling directory ({@code <storagePath>/locks/<UploadId>.lock.stage.<uuid>}) with {@code
 *       lease.json} pre-populated, then atomically moves it into place using {@link
 *       StandardCopyOption#ATOMIC_MOVE}. Directory moves map to atomic server-side RPCs on POSIX
 *       NFS ({@code rename(2)}) and Windows SMB ({@code SetFileInformationByHandle}), ensuring
 *       {@code <UploadId>.lock} is born on disk 100% valid and eliminating empty directory race
 *       windows.
 *   <li><b>Deterministic TTL Leases & Crash Recovery</b>: Each lock directory contains a small JSON
 *       lease file ({@code lease.json}) with an absolute expiration timestamp. If a node crashes
 *       ungracefully ({@code kill -9}, OOM), the lease auto-expires within the TTL (default 30s),
 *       allowing peer cluster nodes to evict and re-acquire the lock without admin intervention.
 *   <li><b>Heartbeat Lease Auto-Renewal</b>: Active locks run a background daemon thread that
 *       periodically updates {@code expiresAt} in {@code lease.json} every {@code leaseDuration /
 *       3} (e.g. every 10s for a 30s lease), preventing lock expiration during long streaming
 *       uploads.
 *   <li><b>TOCTOU Mitigation with Post-Move Verification & Rollback</b>: When multiple nodes race
 *       to evict an expired lock simultaneously, eviction isolates the directory via atomic move to
 *       a unique {@code .evicting.<uuid>} directory and re-inspects the lease post-move. If an
 *       active lease is detected (created by a concurrent winning peer right before the move), the
 *       move is automatically rolled back and eviction is aborted, ensuring single-winner
 *       exclusivity.
 *   <li><b>5-Second Directory Grace Period</b>: Fallback protection for un-staged or corrupted
 *       directories: if a contender encounters a directory where {@code lease.json} is missing or
 *       corrupted and the directory is newer than 5 seconds, it is treated as actively acquiring;
 *       if older than 5 seconds, it is treated as abandoned and evicted.
 *   <li><b>Cross-Replica Lock Contention & .stop Signals</b>: When a concurrent request arrives for
 *       a locked upload (e.g. HEAD or DELETE while a PATCH is streaming), the service writes a
 *       {@code <storagePath>/locks/<UploadId>.stop} signal file. A background watchdog thread polls
 *       every 1.5 seconds and interrupts the active input stream immediately, allowing the resuming
 *       request to proceed without false lock conflicts.
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
  protected UploadLock tryAcquireLock(UploadId uploadId, String holderId, String requestUri)
      throws IOException {
    Path lockDirPath = getLockDirPath(uploadId);
    Path stopFilePath = getStopFilePath(uploadId);

    if (Files.exists(lockDirPath)) {
      return null;
    }

    Path stageDir =
        lockDirPath.resolveSibling(lockDirPath.getFileName() + ".stage." + UUID.randomUUID());
    try {
      Utils.ensureDirectoryExists(lockDirPath.getParent());
      Files.createDirectory(stageDir);

      LeaseFileUploadLock lock =
          new LeaseFileUploadLock(
              lockDirPath, stopFilePath, holderId, leaseDurationMs, requestUri, activeInputStreams);

      // Write lease metadata JSON file inside the staged directory
      Path leaseFile = stageDir.resolve("lease.json");
      Utils.writeJson(lock, leaseFile, false);

      // Atomically move the staged directory to the target lock directory.
      // This guarantees that the lock directory appears atomically with a fully valid lease.json
      // inside it, completely eliminating any empty-directory or partial-write windows.
      Files.move(stageDir, lockDirPath, StandardCopyOption.ATOMIC_MOVE);

      // Clear any lingering stop signal file from prior contention
      try {
        Files.deleteIfExists(stopFilePath);
      } catch (IOException ignored) {
        // Safe to ignore
      }

      return lock;
    } catch (FileSystemException e) {
      // Lock directory already exists or cannot be moved atomically over existing dir
      return null;
    } catch (Exception e) {
      log.debug("Error initializing lease for lock directory {}", lockDirPath, e);
      return null;
    } finally {
      if (Files.exists(stageDir)) {
        try {
          FileUtils.deleteDirectory(stageDir.toFile());
        } catch (IOException ignored) {
          // Safe to ignore
        }
      }
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
    if (lockDirPath == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    // 1. Fast pre-check: avoid moving if we can already observe it is active
    if (!isLockDirectoryExpired(lockDirPath, now)) {
      return false;
    }

    Path evictPath =
        lockDirPath.resolveSibling(lockDirPath.getFileName() + ".evicting." + UUID.randomUUID());
    try {
      // Atomic directory rename guarantees exactly one contender wins the right to isolate and
      // evict
      Files.move(lockDirPath, evictPath, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      // Another contender already moved or removed the directory; safe to proceed
      return false;
    }

    // 2. Post-move verification (TOCTOU mitigation): ensure the isolated directory was genuinely
    // expired and not a fresh active lock created by another winning node right before our move
    now = System.currentTimeMillis();
    if (!isLockDirectoryExpired(evictPath, now)) {
      // We moved a fresh active lock: restore it immediately to preserve the active lock holder
      try {
        Files.move(evictPath, lockDirPath, StandardCopyOption.ATOMIC_MOVE);
      } catch (Exception ignored) {
      }
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
