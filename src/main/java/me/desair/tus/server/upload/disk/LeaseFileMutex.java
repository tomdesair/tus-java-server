package me.desair.tus.server.upload.disk;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;

/**
 * An atomic filesystem mutex directory ({@code <UploadId>.mutex/}) providing mutual exclusion,
 * crash recovery, and {@link AutoCloseable} resource management across concurrent processes and
 * cluster replicas.
 *
 * <p>Atomic directory creation ({@link Files#createDirectory(Path)}) maps to atomic {@code mkdir}
 * across POSIX filesystems, Windows, and shared network storage (NFS v3/v4, AWS EFS, SMB/CIFS).
 *
 * <p>Constructors attempt acquisition immediately. Callers verify {@link #isAcquired()} inside a
 * {@code try-with-resources} block. {@link #close()} only releases the directory if acquisition
 * succeeded.
 */
public class LeaseFileMutex implements AutoCloseable {

  public static final long MUTEX_GRACE_PERIOD_MS = 5_000L; // 5 seconds timeout for stale mutexes

  private final Path mutexDir;
  private boolean acquired;

  /**
   * Constructs a LeaseFileMutex for a target lock directory path and attempts acquisition
   * immediately.
   *
   * @param lockDirPath Path to the {@code <UploadId>.lock} directory
   */
  public LeaseFileMutex(Path lockDirPath) {
    this.mutexDir = resolveMutexDir(lockDirPath);
    this.acquired = tryAcquire();
  }

  /**
   * Constructs a LeaseFileMutex for a given base storage path and upload ID and attempts
   * acquisition immediately.
   *
   * @param storagePath Path to the base locks storage directory
   * @param uploadId The upload identifier
   */
  public LeaseFileMutex(Path storagePath, UploadId uploadId) {
    this.mutexDir =
        (storagePath != null && uploadId != null)
            ? storagePath.resolve(uploadId.toString() + ".mutex")
            : null;
    this.acquired = tryAcquire();
  }

  /**
   * Constructs a LeaseFileMutex with an explicit mutex directory path and attempts acquisition
   * immediately.
   *
   * @param mutexDir Path to the mutex directory
   * @param isExplicitPath Flag indicating explicit path usage
   */
  public LeaseFileMutex(Path mutexDir, boolean isExplicitPath) {
    this.mutexDir = mutexDir;
    this.acquired = tryAcquire();
  }

  /**
   * Indicates whether the mutex directory was successfully acquired.
   *
   * @return {@code true} if acquired; {@code false} otherwise
   */
  public boolean isAcquired() {
    return acquired;
  }

  /**
   * Returns the underlying filesystem path to the mutex directory.
   *
   * @return Path to the mutex directory
   */
  public Path getPath() {
    return mutexDir;
  }

  /**
   * Attempts to acquire the mutex directory via atomic {@link Files#createDirectory(Path)}.
   *
   * <p>If a collision occurs ({@link FileAlreadyExistsException}) and the existing directory's
   * modification time is older than {@link #MUTEX_GRACE_PERIOD_MS} (5s), it is treated as abandoned
   * by a crashed node, cleaned up, and retried once.
   *
   * @return {@code true} if the mutex directory was successfully created; {@code false} if held by
   *     a live contender or I/O error occurred
   */
  private boolean tryAcquire() {
    if (mutexDir == null) {
      return false;
    }
    try {
      Utils.ensureDirectoryExists(mutexDir.getParent());
      Files.createDirectory(mutexDir);
      return true;
    } catch (FileAlreadyExistsException e) {
      try {
        FileTime mtime = Files.getLastModifiedTime(mutexDir);
        long ageMs = System.currentTimeMillis() - mtime.toMillis();
        if (ageMs >= MUTEX_GRACE_PERIOD_MS) {
          // Mutex holder crashed; clean up stale mutex directory and retry once
          FileUtils.deleteDirectory(mutexDir.toFile());
          Files.createDirectory(mutexDir);
          return true;
        }
      } catch (Exception ignored) {
        // Another thread or process resolved it
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /** Releases the mutex by deleting the directory from disk if it was acquired by this instance. */
  public void release() {
    if (acquired && mutexDir != null) {
      try {
        Utils.deletePathQuietly(mutexDir);
      } finally {
        acquired = false;
      }
    }
  }

  /**
   * Releases the mutex when exiting a try-with-resources block if it was acquired by this instance.
   */
  @Override
  public void close() {
    release();
  }

  /**
   * Resolves the sibling mutex directory path for a given lock directory path.
   *
   * @param lockDirPath Path to the lock directory
   * @return Path to the sibling mutex directory, or null if lockDirPath is null
   */
  public static Path resolveMutexDir(Path lockDirPath) {
    if (lockDirPath == null) {
      return null;
    }
    String fileName = lockDirPath.getFileName().toString();
    String idStr =
        fileName.endsWith(".lock") ? fileName.substring(0, fileName.length() - 5) : fileName;
    return lockDirPath.resolveSibling(idStr + ".mutex");
  }
}
