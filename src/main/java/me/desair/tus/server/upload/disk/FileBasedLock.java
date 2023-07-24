package me.desair.tus.server.upload.disk;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upload locking implementation using the file system file locking mechanism. File locking can also
 * apply to shared network drives. This way the framework supports clustering as long as the upload
 * storage directory is mounted as a shared (network) drive. <br>
 * File locks are also automatically released on application (JVM) shutdown. This means the file
 * locking is not persistent and prevents cleanup and stale lock issues.
 */
public class FileBasedLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(FileBasedLock.class);

  private String uploadUri;
  private FileChannel fileChannel = null;
  protected Path lockPath;

  /** Constructor. */
  public FileBasedLock(String uploadUri, Path lockPath)
      throws UploadAlreadyLockedException, IOException {
    Validate.notBlank(uploadUri, "The upload URI cannot be blank");
    Validate.notNull(lockPath, "The path to the lock cannot be null");
    this.uploadUri = uploadUri;
    this.lockPath = lockPath;

    tryToObtainFileLock();
  }

  private void tryToObtainFileLock() throws UploadAlreadyLockedException, IOException {
    String message = "The upload " + getUploadUri() + " is already locked";

    try {
      // Try to acquire a lock
      fileChannel = createFileChannel();
      FileLock fileLock = Utils.lockFileExclusively(fileChannel);

      // If the upload is already locked, our lock will be null
      if (fileLock == null) {
        fileChannel.close();
        throw new UploadAlreadyLockedException(message);
      }

    } catch (OverlappingFileLockException e) {
      if (fileChannel != null) {
        try {
          fileChannel.close();
        } catch (IOException e1) {
          // Should not happen
        }
      }
      throw new UploadAlreadyLockedException(message);
    } catch (IOException e) {
      throw new IOException(
          "Unable to create or open file required to implement file-based locking", e);
    }
  }

  @Override
  public String getUploadUri() {
    return uploadUri;
  }

  @Override
  public void release() {
    try {
      // Closing the channel will also release the lock
      fileChannel.close();
      Files.deleteIfExists(lockPath);
    } catch (IOException e) {
      log.warn("Unable to release file lock for URI " + getUploadUri(), e);
    }
  }

  @Override
  public void close() throws IOException {
    release();
  }

  protected FileChannel createFileChannel() throws IOException {
    return FileChannel.open(lockPath, CREATE, WRITE);
  }
}
