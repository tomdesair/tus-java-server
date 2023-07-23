package me.desair.tus.server.upload.disk;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import org.apache.commons.lang3.Validate;

/**
 * {@link UploadLockingService} implementation that uses the file system for implementing locking
 * <br>
 * File locking can also apply to shared network drives. This way the framework supports clustering
 * as long as the upload storage directory is mounted as a shared (network) drive. <br>
 * File locks are also automatically released on application (JVM) shutdown. This means the file
 * locking is not persistent and prevents cleanup and stale lock issues.
 */
public class DiskLockingService extends AbstractDiskBasedService implements UploadLockingService {

  private static final String LOCK_SUB_DIRECTORY = "locks";

  private UploadIdFactory idFactory;

  public DiskLockingService(String storagePath) {
    super(storagePath + File.separator + LOCK_SUB_DIRECTORY);
  }

  /** Constructor to use custom UploadIdFactory. */
  public DiskLockingService(UploadIdFactory idFactory, String storagePath) {
    this(storagePath);
    Validate.notNull(idFactory, "The IdFactory cannot be null");
    this.idFactory = idFactory;
  }

  @Override
  public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {

    UploadId id = idFactory.readUploadId(requestUri);

    UploadLock lock = null;

    Path lockPath = getLockPath(id);
    // If lockPath is not null, we know this is a valid Upload URI
    if (lockPath != null) {
      lock = new FileBasedLock(requestUri, lockPath);
    }
    return lock;
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    try (DirectoryStream<Path> locksStream = Files.newDirectoryStream(getStoragePath())) {
      for (Path path : locksStream) {

        FileTime lastModifiedTime = Files.getLastModifiedTime(path);
        if (lastModifiedTime.toMillis() < System.currentTimeMillis() - 10000L) {
          UploadId id = new UploadId(path.getFileName().toString());

          if (!isLocked(id)) {
            Files.deleteIfExists(path);
          }
        }
      }
    }
  }

  @Override
  public boolean isLocked(UploadId id) {
    boolean locked = false;
    Path lockPath = getLockPath(id);

    if (lockPath != null) {
      // Try to obtain a lock to see if the upload is currently locked
      try (UploadLock lock = new FileBasedLock(id.toString(), lockPath)) {

        // We got the lock, so it means no one else is locking it.
        locked = false;

      } catch (UploadAlreadyLockedException | IOException e) {
        // There was already a lock
        locked = true;
      }
    }

    return locked;
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    Validate.notNull(idFactory, "The IdFactory cannot be null");
    this.idFactory = idFactory;
  }

  private Path getLockPath(UploadId id) {
    return getPathInStorageDirectory(id);
  }
}
