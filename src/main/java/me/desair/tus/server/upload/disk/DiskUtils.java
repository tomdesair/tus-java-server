package me.desair.tus.server.upload.disk;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.serialization.ValidatingObjectInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility helper methods for disk-based storage, file locking, and path validation. */
public final class DiskUtils {

  private static final Logger log = LoggerFactory.getLogger(DiskUtils.class);
  private static final int LOCK_FILE_RETRY_COUNT = 3;
  private static final long LOCK_FILE_SLEEP_TIME = 500;

  private DiskUtils() {
    // Utility class
  }

  /**
   * Safely resolves a child path within a base directory, validating against directory traversal
   * attempts.
   *
   * @param basePath The base parent directory path
   * @param child The child filename or relative path
   * @param errorMessage The error message to include if path traversal is detected
   * @return The resolved child path, or null if basePath or child is null
   * @throws IllegalArgumentException If path traversal is detected
   */
  public static Path resolveSafePath(Path basePath, String child, String errorMessage) {
    if (basePath == null || child == null) {
      return null;
    }
    Path path = basePath.resolve(child);
    if (!path.normalize().toAbsolutePath().startsWith(basePath.normalize().toAbsolutePath())) {
      throw new IllegalArgumentException(errorMessage);
    }
    return path;
  }

  /**
   * Initializes a disk storage directory, ensuring it exists or throwing {@link
   * StoragePathNotAvailableException} if directory creation fails.
   *
   * @param storagePath The directory path to initialize
   * @throws StoragePathNotAvailableException If directory creation fails
   */
  public static void initStorageDirectory(Path storagePath) {
    Objects.requireNonNull(storagePath, "storagePath must not be null");
    try {
      Utils.ensureDirectoryExists(storagePath);
    } catch (IOException e) {
      String message =
          "Unable to create the directory specified by the storage path " + storagePath;
      log.error(message, e);
      throw new StoragePathNotAvailableException(message, e);
    }
  }

  /**
   * Checks whether a given path component string is safe for use as a file or directory name on
   * disk across platforms (preventing path traversal sequences and invalid characters).
   *
   * @param component The path component to validate
   * @return {@code true} if safe; {@code false} otherwise
   */
  public static boolean isSafePathComponent(String component) {
    if (component == null || component.trim().isEmpty() || component.contains("..")) {
      return false;
    }
    for (int i = 0; i < component.length(); i++) {
      char c = component.charAt(i);
      if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
          || c == '<' || c == '>' || c == '|') {
        return false;
      }
    }
    return true;
  }

  /**
   * Normalizes a checksum value string, converting Base64-encoded checksums to lowercase hex string
   * format if necessary.
   *
   * @param checksum Raw checksum string
   * @return Normalized hex string checksum, or the original string if null or already hex
   */
  public static String normalizeChecksum(String checksum) {
    String filename = checksum;
    if (filename != null) {
      if (!filename.matches("^[0-9a-fA-F]+$")) {
        byte[] bytes = Base64.decodeBase64(filename);
        if (bytes != null && bytes.length > 0) {
          StringBuilder sb = new StringBuilder();
          for (byte b : bytes) {
            sb.append(String.format("%02x", b));
          }
          filename = sb.toString();
        }
      }
    }
    return filename;
  }

  /**
   * Checks whether a file or directory is older than a specified age in milliseconds relative to a
   * given reference timestamp.
   *
   * @param path The file or directory path
   * @param maxAgeMs The maximum age threshold in milliseconds
   * @param now The reference timestamp in milliseconds
   * @return {@code true} if older than or equal to maxAgeMs; {@code false} if younger, path is
   *     null, or an I/O exception occurs
   */
  public static boolean isPathOlderThan(Path path, long maxAgeMs, long now) {
    if (path == null) {
      return false;
    }
    try {
      FileTime mtime = Files.getLastModifiedTime(path);
      return (now - mtime.toMillis()) >= maxAgeMs;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Checks whether a file or directory is older than a specified age in milliseconds relative to
   * current system time.
   *
   * @param path The file or directory path
   * @param maxAgeMs The maximum age threshold in milliseconds
   * @return {@code true} if older than or equal to maxAgeMs; {@code false} otherwise
   */
  public static boolean isPathOlderThan(Path path, long maxAgeMs) {
    return isPathOlderThan(path, maxAgeMs, System.currentTimeMillis());
  }

  /**
   * Recursively deletes a directory and all of its contents quietly without throwing exceptions.
   *
   * @param directory The directory path to delete recursively
   * @return {@code true} if deleted successfully; {@code false} otherwise
   */
  public static boolean deleteDirectoryQuietly(Path directory) {
    if (directory == null || !Files.exists(directory)) {
      return false;
    }
    try {
      Files.walkFileTree(
          directory,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              Utils.deletePathQuietly(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
              Utils.deletePathQuietly(dir);
              return FileVisitResult.CONTINUE;
            }
          });
      return !Files.exists(directory);
    } catch (IOException e) {
      log.debug("Failed to delete directory quietly: {}", directory, e);
      return false;
    }
  }

  /**
   * Resolves a unique temporary sibling path for a target destination file path.
   *
   * @param targetPath The destination file path
   * @return A temporary sibling path with a unique UUID suffix, or null if targetPath is null
   */
  public static Path createTempSiblingPath(Path targetPath) {
    if (targetPath == null) {
      return null;
    }
    Path parent = targetPath.getParent();
    String tempFileName = targetPath.getFileName().toString() + ".tmp." + UUID.randomUUID();
    return parent != null ? parent.resolve(tempFileName) : Paths.get(tempFileName);
  }

  /**
   * Creates an {@link AutoCloseable} {@link TempPath} that generates a unique temporary sibling
   * path and automatically deletes it upon closing if it still exists.
   *
   * @param targetPath The destination file path
   * @return An AutoCloseable TempPath instance
   */
  public static TempPath createTempSibling(Path targetPath) {
    return new TempPath(targetPath);
  }

  /**
   * AutoCloseable temporary sibling path that automatically cleans up (deletes) the temporary file
   * upon closing if it still exists.
   */
  public static class TempPath implements AutoCloseable {
    private final Path path;

    /**
     * Constructs a TempPath resolving a unique sibling path for the given target path.
     *
     * @param targetPath The destination file path
     */
    public TempPath(Path targetPath) {
      this.path = createTempSiblingPath(targetPath);
    }

    /**
     * Returns the underlying temporary sibling {@link Path}.
     *
     * @return The temporary path, or null if targetPath was null
     */
    public Path getPath() {
      return path;
    }

    @Override
    public void close() {
      Utils.deletePathQuietly(path);
    }
  }

  /**
   * Atomically moves a source file to a destination path, replacing any existing destination file.
   * On network file systems (such as NFS mounts or Kubernetes Persistent Volumes) where {@code
   * ATOMIC_MOVE} is unsupported by the filesystem driver, this method falls back gracefully to
   * standard {@code REPLACE_EXISTING} move to prevent unexpected I/O failures.
   *
   * @param source The source file path to move
   * @param destination The target destination file path
   * @throws IOException If moving the file fails
   */
  public static void atomicMove(Path source, Path destination) throws IOException {
    if (source != null && destination != null) {
      try {
        Files.move(
            source,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        // On network filesystems (e.g. NFS shares or Kubernetes PVs), ATOMIC_MOVE fails with
        // AtomicMoveNotSupportedException. Fall back to standard REPLACE_EXISTING move.
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  /**
   * Reads a serializable object from disk.
   *
   * @param <T> Target object type
   * @param path The file path to read from
   * @param clazz The target object class
   * @return Deserialized object instance, or null if reading fails or file does not exist
   * @throws IOException If file access fails
   */
  public static <T> T readSerializable(Path path, Class<T> clazz) throws IOException {
    T info = null;
    if (path != null && Files.exists(path)) {
      try (InputStream is = Files.newInputStream(path);
          ValidatingObjectInputStream ois = new ValidatingObjectInputStream(is)) {
        if (clazz != null) {
          ois.accept(clazz);
        }
        ois.accept(
            UploadInfo.class,
            UploadId.class,
            UploadType.class,
            ChecksumAlgorithm.class,
            String.class,
            Long.class,
            Integer.class,
            Boolean.class,
            Number.class,
            Object.class,
            Enum.class,
            Date.class,
            UUID.class,
            ArrayList.class,
            LinkedList.class,
            TreeMap.class,
            HashMap.class,
            HashSet.class,
            TreeSet.class,
            String[].class,
            Object[].class,
            Arrays.asList().getClass(),
            Collections.unmodifiableList(new ArrayList<>()).getClass(),
            Collections.emptyList().getClass());
        info = clazz.cast(ois.readObject());
      } catch (ClassNotFoundException
          | InvalidClassException
          | EOFException
          | StreamCorruptedException e) {
        // File may be corrupted due to unexpected server shutdown or contain an unallowed class
        log.warn("Unable to read serializable file {}: {}", path, e.getMessage());
        info = null;
      }
    }
    return info;
  }

  /**
   * Writes a serializable object to a file on disk atomically via a temporary file rename.
   *
   * @param object The serializable object to write
   * @param path The file path to write to
   * @throws IOException If file access or move fails
   */
  public static void writeSerializable(Serializable object, Path path) throws IOException {
    if (path != null && object != null) {
      try (TempPath tempPath = new TempPath(path)) {
        try (OutputStream os =
                Files.newOutputStream(tempPath.getPath(), WRITE, CREATE, TRUNCATE_EXISTING);
            OutputStream buffer = new BufferedOutputStream(os);
            ObjectOutput output = new ObjectOutputStream(buffer)) {

          output.writeObject(object);
        }
        atomicMove(tempPath.getPath(), path);
      }
    }
  }

  /**
   * Locks the given file channel exclusively.
   *
   * @param channel The FileChannel to lock
   * @return The acquired FileLock, or null if lock acquisition failed
   * @throws IOException If an I/O error occurs
   */
  public static FileLock lockFileExclusively(FileChannel channel) throws IOException {
    return lockFile(channel, false);
  }

  /**
   * Locks the given file channel with a shared lock.
   *
   * @param channel The FileChannel to lock
   * @return The acquired FileLock, or null if lock acquisition failed
   * @throws IOException If an I/O error occurs
   */
  public static FileLock lockFileShared(FileChannel channel) throws IOException {
    return lockFile(channel, true);
  }

  private static FileLock lockFile(FileChannel channel, boolean shared) throws IOException {
    int i = 0;
    FileLock lock = null;
    do {
      if (i > 0) {
        Utils.sleep(LOCK_FILE_SLEEP_TIME);
      }

      lock = channel.tryLock(0L, Long.MAX_VALUE, shared);

      i++;
    } while (lock == null && i < LOCK_FILE_RETRY_COUNT);

    return lock;
  }

  /**
   * Safely interrupts and stops a thread, catching and logging any exceptions that occur.
   *
   * @param thread The Thread to interrupt
   */
  public static void interruptThread(Thread thread) {
    if (thread != null) {
      try {
        thread.interrupt();
      } catch (Exception e) {
        log.debug("Error interrupting thread {}: {}", thread.getName(), e.getMessage());
      }
    }
  }
}
