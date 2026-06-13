package me.desair.tus.server.upload.disk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link UploadLockingService} implementation that uses the file system for implementing locking
 * <br>
 * File locking can also apply to shared network drives. This way the framework supports clustering
 * as long as the upload storage directory is mounted as a shared (network) drive. <br>
 * File locks are also automatically released on application (JVM) shutdown. This means the file
 * locking is not persistent and prevents cleanup and stale lock issues.
 */
public class DiskLockingService extends AbstractDiskBasedService implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(DiskLockingService.class);
  private static final String LOCK_SUB_DIRECTORY = "locks";

  /** Registry tracking active request input streams in the current JVM. */
  private static final ConcurrentHashMap<String, WeakReference<InterruptibleInputStream>>
      activeLocks = new ConcurrentHashMap<>();

  private static Thread watchdogThread = null;
  private static final Object watchdogLock = new Object();

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

  /**
   * Attempts to lock the upload resource. Wraps the lock in a RegisteredLock decorator to manage
   * cleanup of stop files and the active lock registry.
   */
  @Override
  public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {

    UploadId id = idFactory.readUploadId(requestUri);

    UploadLock lock = null;

    Path lockPath = getLockPath(id);
    // If lockPath is not null, we know this is a valid Upload URI
    if (lockPath != null) {
      FileBasedLock baseLock = new FileBasedLock(requestUri, lockPath);
      Path stopFilePath = baseLock.getLockPath().resolveSibling(id.toString() + ".stop");
      lock = new RegisteredLock(baseLock, id.toString(), stopFilePath);
    }
    return lock;
  }

  /** Cleans up stale locks and stop files in the storage directory. */
  @Override
  public void cleanupStaleLocks() throws IOException {
    try (DirectoryStream<Path> locksStream = Files.newDirectoryStream(getStoragePath())) {
      for (Path path : locksStream) {
        if (Files.exists(path)) {
          FileTime lastModifiedTime = Files.getLastModifiedTime(path);
          if (lastModifiedTime.toMillis() < System.currentTimeMillis() - 10000L) {
            String fileName = path.getFileName().toString();
            if (fileName.endsWith(".stop")) {
              Files.deleteIfExists(path);
            } else {
              UploadId id = new UploadId(fileName);
              if (!isLocked(id)) {
                Files.deleteIfExists(path);
                Files.deleteIfExists(path.resolveSibling(fileName + ".stop"));
              }
            }
          }
        }
      }
    }
  }

  /** Checks whether the upload is locked by attempting to obtain a short-lived file lock. */
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

  /**
   * Registers an active request input stream so that it can be interrupted if lock contention
   * occurs.
   */
  @Override
  public void registerInputStream(String requestUri, InputStream inputStream) {
    if (inputStream == null) {
      return;
    }
    UploadId id = idFactory.readUploadId(requestUri);
    if (id == null) {
      return;
    }
    if (inputStream instanceof InterruptibleInputStream) {
      activeLocks.put(id.toString(), new WeakReference<>((InterruptibleInputStream) inputStream));
      startWatchdogIfNecessary();
    }
  }

  /**
   * Requests that the lock for the given URI be released, interrupting the active stream and
   * creating a stop file to signal other replicas.
   */
  @Override
  public void requestLockRelease(String requestUri) {
    UploadId id = idFactory.readUploadId(requestUri);
    if (id == null) {
      return;
    }
    String idStr = id.toString();

    // 1. Release JVM-local lock if active
    WeakReference<InterruptibleInputStream> streamRef = activeLocks.get(idStr);
    if (streamRef != null) {
      InterruptibleInputStream stream = streamRef.get();
      if (stream != null) {
        stream.interrupt();
      }
      activeLocks.remove(idStr);
    }

    // 2. Create the stop file to signal other replicas
    Path stopFilePath = getStopPath(id);
    if (stopFilePath != null) {
      try {
        Path parentDir = stopFilePath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
          Files.createDirectories(parentDir);
        }
        Files.write(stopFilePath, new byte[0]);
      } catch (IOException e) {
        log.warn("Unable to create stop file " + stopFilePath, e);
      }
    }
  }

  /** Spawns a new background watchdog thread if none is currently active. */
  private void startWatchdogIfNecessary() {
    synchronized (watchdogLock) {
      if (watchdogThread == null || !watchdogThread.isAlive()) {
        watchdogThread = new Thread(new LockWatchdogRunnable(), "tus-lock-watchdog");
        watchdogThread.setDaemon(true);
        // Set lowest priority to ensure request threads are prioritized by the OS scheduler
        watchdogThread.setPriority(Thread.MIN_PRIORITY);
        watchdogThread.start();
      }
    }
  }

  private Path getLockPath(UploadId id) {
    return getPathInStorageDirectory(id);
  }

  /**
   * Resolves the stop file path based on the upload ID, ensuring files reside in the correct
   * directory.
   */
  private Path getStopPath(UploadId id) {
    Path lockPath = getPathInStorageDirectory(id);
    return lockPath == null ? null : lockPath.resolveSibling(id.toString() + ".stop");
  }

  /**
   * Runnable implementation for the background watchdog thread. This thread polls the storage
   * directory for ".stop" files created by other processes or replicas. If a stop file is found for
   * an active local upload, it interrupts the request stream to release the lock. The thread
   * self-terminates when there are no more active local locks to monitor.
   */
  private class LockWatchdogRunnable implements Runnable {
    @Override
    public void run() {
      try {
        while (true) {
          try {
            Thread.sleep(1000L);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }

          // Check stop files for each active lock
          for (Map.Entry<String, WeakReference<InterruptibleInputStream>> entry :
              activeLocks.entrySet()) {
            String idStr = entry.getKey();
            WeakReference<InterruptibleInputStream> ref = entry.getValue();
            InterruptibleInputStream stream = ref.get();

            if (stream == null) {
              activeLocks.remove(idStr);
              continue;
            }

            Path stopFilePath = getStopPath(new UploadId(idStr));
            if (stopFilePath != null && Files.exists(stopFilePath)) {
              try {
                log.info(
                    "Watchdog detected stop file for upload ID {}. Interrupting stream.", idStr);
                stream.interrupt();
              } catch (Throwable t) {
                log.warn("Error interrupting stream for ID " + idStr, t);
              }
              activeLocks.remove(idStr);
              try {
                Files.deleteIfExists(stopFilePath);
              } catch (IOException e) {
                // ignore
              }
            }
          }

          // Thread-safe check to decide whether to exit
          if (activeLocks.isEmpty()) {
            synchronized (watchdogLock) {
              if (activeLocks.isEmpty()) {
                watchdogThread = null;
                break;
              }
            }
          }
        }
      } catch (Throwable t) {
        log.error("Lock watchdog encountered an unexpected error", t);
        synchronized (watchdogLock) {
          watchdogThread = null;
        }
      }
    }
  }

  /**
   * Decorator around UploadLock to manage local map registry cleanup and stop file deletion when
   * the lock is released or closed.
   */
  private class RegisteredLock implements UploadLock {
    private final UploadLock delegate;
    private final String uploadIdStr;
    private final Path stopFilePath;

    public RegisteredLock(UploadLock delegate, String uploadIdStr, Path stopFilePath) {
      this.delegate = delegate;
      this.uploadIdStr = uploadIdStr;
      this.stopFilePath = stopFilePath;
    }

    @Override
    public String getUploadUri() {
      return delegate.getUploadUri();
    }

    @Override
    public void release() {
      try {
        delegate.release();
      } finally {
        activeLocks.remove(uploadIdStr);
        if (stopFilePath != null) {
          try {
            Files.deleteIfExists(stopFilePath);
          } catch (IOException e) {
            log.warn("Unable to delete stop file " + stopFilePath, e);
          }
        }
      }
    }

    @Override
    public void close() throws IOException {
      try {
        delegate.close();
      } finally {
        activeLocks.remove(uploadIdStr);
        if (stopFilePath != null) {
          try {
            Files.deleteIfExists(stopFilePath);
          } catch (IOException e) {
            log.warn("Unable to delete stop file " + stopFilePath, e);
          }
        }
      }
    }
  }
}
