package me.desair.tus.server.upload.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed {@link UploadLockingService} backed by Azure Blob Storage Leases.
 *
 * <p><b>Azure Distributed Locking & Contention Mechanics:</b>
 *
 * <ul>
 *   <li><b>Azure Blob Leases</b>: Locks are backed by 30-second native Azure Blob Leases on
 *       dedicated lock target blobs (e.g. {@code locks/<uploadId>.lock}). If another pod or thread
 *       attempts to acquire a lease on a locked blob, Azure returns HTTP 409 Conflict, which is
 *       translated to an {@link UploadAlreadyLockedException}.
 *   <li><b>Heartbeat Lease Renewal</b>: Active locks automatically renew their lease via a
 *       background renewal thread in {@link AzureBlobUploadLock}, keeping the lock alive during
 *       long uploads.
 *   <li><b>Auto-Expiry on Node Crash</b>: If a pod crashes unexpectedly (e.g., OOM or {@code kill
 *       -9}), Azure automatically releases the lease after 30 seconds, preventing permanent
 *       deadlocks.
 *   <li><b>Cross-Pod Contention & Interruption</b>: When a concurrent request (e.g., HEAD or
 *       DELETE) arrives for a locked upload, the service interrupts local streams and writes a
 *       {@code locks/<uploadId>.stop} signal blob to Azure. A background watchdog thread detects
 *       the {@code .stop} file and interrupts streaming on remote pods.
 * </ul>
 */
public class AzureBlobLockingService implements UploadLockingService, Closeable {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobLockingService.class);

  public static final String DEFAULT_LOCKS_PREFIX = "locks/";
  private static final int LEASE_DURATION_SECONDS = 30;

  private final BlobContainerClient containerClient;
  private final String locksPrefix;
  private final Map<String, WeakReference<InterruptibleInputStream>> activeStreams =
      new ConcurrentHashMap<>();

  private UploadIdFactory idFactory = new UuidUploadIdFactory();

  private Thread watchdogThread = null;
  private final Object watchdogLock = new Object();

  private final Thread shutdownHook;
  private volatile boolean closed = false;

  /**
   * Constructs an {@link AzureBlobLockingService} with default lock key prefix.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   */
  public AzureBlobLockingService(BlobContainerClient containerClient) {
    this(containerClient, DEFAULT_LOCKS_PREFIX);
  }

  /**
   * Constructs an {@link AzureBlobLockingService} with customizable lock key prefix.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   * @param locksPrefix Blob name prefix for lock objects
   */
  public AzureBlobLockingService(BlobContainerClient containerClient, String locksPrefix) {
    this.containerClient =
        Objects.requireNonNull(containerClient, "containerClient must not be null");
    this.locksPrefix = sanitizePrefix(locksPrefix);

    // Register automatic JVM shutdown hook to clean up watchdog threads on app/pod shutdown
    this.shutdownHook = new Thread(this::closeQuietly, "azure-lock-shutdown-hook");
    registerShutdownHook();
  }

  private void registerShutdownHook() {
    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  private void deregisterShutdownHook() {
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException ignored) {
      // JVM is already shutting down
    }
  }

  private void closeQuietly() {
    try {
      close();
    } catch (Exception ignored) {
    }
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      deregisterShutdownHook();
      synchronized (watchdogLock) {
        if (watchdogThread != null) {
          watchdogThread.interrupt();
          watchdogThread = null;
        }
      }
      activeStreams.clear();
    }
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    this.idFactory = Objects.requireNonNull(idFactory, "idFactory must not be null");
  }

  @Override
  public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId == null) {
      return null;
    }
    String idStr = uploadId.toString();

    // 1. Ensure lock target blob exists on Azure Storage under locksPrefix
    BlobClient lockBlob = containerClient.getBlobClient(locksPrefix + idStr + ".lock");
    ensureLockBlobExists(lockBlob);

    // 2. Instantiate Azure Blob Lease client for target lock blob
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();

    try {
      // 3. Acquire 30-second exclusive lease from Azure Blob Storage
      leaseClient.acquireLease(LEASE_DURATION_SECONDS);

      // Lock successfully acquired: clear any lingering .stop signal blob
      deleteStopSignalBlob(idStr);

      return new AzureBlobUploadLock(leaseClient, lockBlob, requestUri);
    } catch (BlobStorageException e) {
      AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
      if (errorType == AzureErrorType.LEASE_ALREADY_PRESENT
          || errorType == AzureErrorType.CONFLICT) {
        log.info("Lock contention for upload URI {}: Azure blob lease is already held", requestUri);
        throw new UploadAlreadyLockedException(
            "Upload with URI " + requestUri + " is currently locked");
      }
      throw new IOException("Failed to acquire Azure blob lease lock for URI " + requestUri, e);
    }
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    // Azure Blob Leases auto-expire after 30s on holder failure; no manual sweeps needed
  }

  @Override
  public boolean isLocked(UploadId id) {
    if (id == null) {
      return false;
    }
    BlobClient lockBlob = containerClient.getBlobClient(locksPrefix + id + ".lock");
    try {
      // Single HEAD call to fetch properties and check if LeaseState is "leased"
      BlobProperties props = lockBlob.getProperties();
      return props.getLeaseState() != null
          && Strings.CS.equals(props.getLeaseState().toString(), "leased");
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  public void registerInputStream(String requestUri, InputStream inputStream) {
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId != null && inputStream instanceof InterruptibleInputStream) {
      activeStreams.put(
          uploadId.toString(), new WeakReference<>((InterruptibleInputStream) inputStream));
      ensureWatchdogRunning();
    }
  }

  @Override
  public void requestLockRelease(String requestUri) {
    UploadId uploadId = idFactory.readUploadId(requestUri);
    if (uploadId != null) {
      String idStr = uploadId.toString();
      // 1. Interrupt active local input stream in JVM
      interruptLocalStream(idStr);
      // 2. Write cross-pod .stop signal blob to notify remote pods
      createStopSignalBlob(idStr);
    }
  }

  /** Interrupts active JVM-local input stream for the given upload ID. */
  private void interruptLocalStream(String idStr) {
    WeakReference<InterruptibleInputStream> streamRef = activeStreams.remove(idStr);
    if (streamRef != null) {
      InterruptibleInputStream stream = streamRef.get();
      if (stream != null) {
        log.info("Interrupting JVM-local stream for upload ID {}", idStr);
        Utils.interruptStream(stream);
      }
    }
  }

  /** Creates a .stop signal blob to request remote pods to halt active streaming appends. */
  private void createStopSignalBlob(String idStr) {
    try {
      BlobClient stopBlob = containerClient.getBlobClient(locksPrefix + idStr + ".stop");
      stopBlob.upload(BinaryData.fromString("stop"), true);
    } catch (Exception e) {
      log.debug("Failed to write .stop signal blob for upload ID {}: {}", idStr, e.getMessage());
    }
  }

  /** Deletes the .stop signal blob after lock acquisition. */
  private void deleteStopSignalBlob(String idStr) {
    try {
      BlobClient stopBlob = containerClient.getBlobClient(locksPrefix + idStr + ".stop");
      stopBlob.deleteIfExists();
    } catch (Exception ignored) {
      // Ignore cleanup exceptions
    }
  }

  /** Ensures the lock target blob exists on Azure Blob Storage. */
  void ensureLockBlobExists(BlobClient lockBlob) {
    try {
      if (!lockBlob.exists()) {
        lockBlob.upload(BinaryData.fromBytes("lock".getBytes(StandardCharsets.UTF_8)), false);
      }
    } catch (BlobStorageException e) {
      AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
      if (errorType != AzureErrorType.CONFLICT
          && errorType != AzureErrorType.LEASE_ALREADY_PRESENT
          && errorType != AzureErrorType.PRECONDITION_FAILED) {
        log.debug("Lock target blob existence check: {}", e.getMessage());
      }
    } catch (Exception e) {
      log.debug("Lock target blob creation: {}", e.getMessage());
    }
  }

  /** Ensures background watchdog thread is active for polling .stop signal blobs. */
  private void ensureWatchdogRunning() {
    synchronized (watchdogLock) {
      if (watchdogThread == null || !watchdogThread.isAlive()) {
        watchdogThread = new Thread(this::pollStopSignals, "azure-lock-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
      }
    }
  }

  /** Polls for .stop signal blobs every 2 seconds while active streams exist. */
  private void pollStopSignals() {
    while (!Thread.currentThread().isInterrupted() && !activeStreams.isEmpty()) {
      try {
        Thread.sleep(2000L);
        for (String idStr : activeStreams.keySet()) {
          BlobClient stopBlob = containerClient.getBlobClient(locksPrefix + idStr + ".stop");
          if (stopBlob.exists()) {
            log.info("Detected remote .stop signal blob for upload ID {}", idStr);
            interruptLocalStream(idStr);
            stopBlob.deleteIfExists();
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        log.debug("Error in azure-lock-watchdog polling loop: {}", e.getMessage());
      }
    }
  }

  private String sanitizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    String result = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    return result.endsWith("/") ? result : result + "/";
  }
}
