package me.desair.tus.server.upload.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import com.azure.storage.blob.models.LeaseStateType;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.AbstractCloseableResourceService;
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
public class AzureBlobLockingService extends AbstractCloseableResourceService
    implements UploadLockingService {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobLockingService.class);

  public static final String DEFAULT_LOCKS_PREFIX = "locks/";
  private static final int LEASE_DURATION_SECONDS = 30;
  private static final long DEFAULT_STALE_LOCK_THRESHOLD_SECONDS = 120L;
  private static final long STOP_SIGNAL_EXPIRATION_SECONDS = 30L;

  private final BlobContainerClient containerClient;
  private final String locksPrefix;
  final Map<String, WeakReference<InterruptibleInputStream>> activeStreams =
      new ConcurrentHashMap<>();
  final Map<String, AzureBlobUploadLock> activeLocks = new ConcurrentHashMap<>();

  private UploadIdFactory idFactory = new UuidUploadIdFactory();

  private final ScheduledExecutorService watchdogExecutor;

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
    super("azure-lock-shutdown-hook");
    this.containerClient =
        Objects.requireNonNull(containerClient, "containerClient must not be null");
    this.locksPrefix = sanitizePrefix(locksPrefix);

    // Use pooled scheduled daemon executor to poll .stop signals across pods without thread leaks
    this.watchdogExecutor =
        Utils.scheduleWatchdog(
            "azure-lock-watchdog", this::pollStopSignals, 2000L, 2000L, TimeUnit.MILLISECONDS);
  }

  @Override
  protected void cleanupOnClose() throws IOException {
    Utils.shutdownExecutor(watchdogExecutor);
    for (WeakReference<InterruptibleInputStream> streamRef : activeStreams.values()) {
      if (streamRef != null) {
        Utils.interruptStream(streamRef.get());
      }
    }
    activeStreams.clear();
    activeLocks.clear();
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

    // 1. Target lock blob and instantiate Azure Blob Lease client
    BlobClient lockBlob = containerClient.getBlobClient(locksPrefix + idStr + ".lock");
    BlobLeaseClient leaseClient = createBlobLeaseClient(lockBlob);

    try {
      // Optimistic lease acquisition: in ongoing uploads, the .lock blob already exists
      // 99.9% of the time. Calling acquireLease directly eliminates an expensive round-trip
      // lockBlob.exists() call on every lock acquisition.
      try {
        leaseClient.acquireLease(LEASE_DURATION_SECONDS);
      } catch (BlobStorageException e) {
        AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
        if (errorType == AzureErrorType.BLOB_NOT_FOUND) {
          // If lock blob does not exist yet (first upload request), create it and retry acquisition
          // once
          ensureLockBlobExists(lockBlob);
          leaseClient.acquireLease(LEASE_DURATION_SECONDS);
        } else {
          throw e;
        }
      }

      // Lock successfully acquired: clear any lingering .stop signal blob
      deleteStopSignalBlob(idStr);

      AzureBlobUploadLock lock =
          new AzureBlobUploadLock(
              leaseClient, lockBlob, requestUri, idStr, activeStreams, activeLocks);
      activeLocks.put(idStr, lock);
      return lock;
    } catch (BlobStorageException e) {
      AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
      if (errorType == AzureErrorType.LEASE_ALREADY_PRESENT
          || errorType == AzureErrorType.CONFLICT) {
        log.debug(
            "Lock contention for upload URI {}: Azure blob lease is already held", requestUri);
        throw new UploadAlreadyLockedException(
            "Upload with URI " + requestUri + " is currently locked");
      }
      throw new IOException("Failed to acquire Azure blob lease lock for URI " + requestUri, e);
    }
  }

  @Override
  public void cleanupLock(UploadId id) throws IOException {
    if (id == null) {
      return;
    }
    String idStr = id.toString();
    try {
      // 1. Delete any lingering cross-pod .stop signal blob from prior lock contention
      deleteStopSignalBlob(idStr);

      // 2. Delete the target .lock blob in the locks prefix
      BlobClient lockBlob = getBlobClient(locksPrefix + idStr + ".lock");
      AzureBlobUploadLock activeLock = activeLocks.get(idStr);
      if (activeLock != null && activeLock.getLeaseClient() != null) {
        // The lock is actively held by this instance (e.g. during DELETE termination).
        // Safely delete the blob specifying the active lease ID condition.
        deleteLockBlobWithLease(lockBlob, activeLock.getLeaseClient().getLeaseId());
        activeLock.markBlobDeleted();
      } else {
        deleteLockBlobIfUnleased(lockBlob);
      }
    } catch (Exception e) {
      log.debug("Failed to clean up Azure lock blob for upload ID {}: {}", idStr, e.getMessage());
    }
  }

  @Override
  public void cleanupLock(String uploadUri) throws IOException {
    cleanupLock(uploadUri != null && idFactory != null ? idFactory.readUploadId(uploadUri) : null);
  }

  /**
   * Safely deletes an actively leased lock blob using its active lease ID.
   *
   * <p>When an upload is terminated (e.g. via {@code DELETE}) while holding the request lock,
   * standard unleased deletion cannot be used because Azure returns HTTP 412 (LeaseIdMissing).
   * Providing the active lease ID in {@link BlobRequestConditions} allows Azure to delete the
   * actively leased lock blob immediately.
   *
   * @param lockBlob The blob client for the lock blob
   * @param leaseId The active lease ID
   * @return true if successfully deleted, false otherwise
   */
  boolean deleteLockBlobWithLease(BlobClient lockBlob, String leaseId) {
    if (lockBlob == null || leaseId == null) {
      return false;
    }
    try {
      lockBlob.deleteWithResponse(
          DeleteSnapshotsOptionType.INCLUDE,
          new BlobRequestConditions().setLeaseId(leaseId),
          null,
          null);
      log.debug("Cleaned up actively leased Azure lock blob {}", lockBlob.getBlobName());
      return true;
    } catch (Exception e) {
      log.debug("Failed to delete leased lock blob {}: {}", lockBlob.getBlobName(), e.getMessage());
      return false;
    }
  }

  /**
   * Safely deletes an unleased lock blob. Attempting deleteIfExists() on an actively leased blob
   * without the lease ID causes Azure Blob Storage to reject the request with HTTP 412
   * (LeaseIdMissing). This helper verifies lease state before deletion.
   */
  boolean deleteLockBlobIfUnleased(BlobClient lockBlob) {
    if (lockBlob == null) {
      return false;
    }
    try {
      if (Boolean.TRUE.equals(lockBlob.exists())) {
        BlobProperties props = lockBlob.getProperties();
        // Check lease state before deletion: if actively held by another thread or node,
        // do not attempt deletion to avoid lease conflict errors.
        if (props.getLeaseState() != null
            && !Strings.CS.equals(props.getLeaseState().toString(), "leased")) {
          lockBlob.deleteIfExists();
          log.debug("Cleaned up Azure lock blob {}", lockBlob.getBlobName());
          return true;
        }
      }
    } catch (Exception e) {
      log.debug(
          "Failed to delete unleased lock blob {}: {}", lockBlob.getBlobName(), e.getMessage());
    }
    return false;
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    try {
      ListBlobsOptions options = new ListBlobsOptions().setPrefix(locksPrefix);
      OffsetDateTime now = OffsetDateTime.now();

      for (BlobItem item : listBlobs(options)) {
        if (item == null || item.getName() == null) {
          continue;
        }
        String blobName = item.getName();
        BlobItemProperties props = item.getProperties();
        if (props == null) {
          continue;
        }
        // Defensively retrieve properties; uninitialized BlobItem instances in Azure SDK v12
        // throw NullPointerException on property access if internalProperties is null.
        OffsetDateTime lastModified = null;
        LeaseStateType leaseState = null;
        try {
          lastModified = props.getLastModified();
          leaseState = props.getLeaseState();
        } catch (NullPointerException ignored) {
          continue;
        }

        // 1. Clean up stale .stop signal blobs older than 30s
        if (blobName.endsWith(".stop")) {
          if (lastModified != null
              && lastModified.isBefore(now.minusSeconds(STOP_SIGNAL_EXPIRATION_SECONDS))) {
            getBlobClient(blobName).deleteIfExists();
            log.info("Cleaned up stale Azure stop signal blob: {}", blobName);
          }
        }
        // 2. Clean up un-leased .lock blobs older than 120s
        else if (blobName.endsWith(".lock")) {
          // Never delete actively leased blobs to prevent interrupting active upload threads
          if (leaseState != null && leaseState == LeaseStateType.LEASED) {
            continue;
          }
          // The 120s grace period ensures we do not delete lock blobs for uploads actively
          // between chunk requests, while cleaning up abandoned or pre-existing lock blobs.
          if (lastModified != null
              && lastModified.isBefore(now.minusSeconds(DEFAULT_STALE_LOCK_THRESHOLD_SECONDS))) {
            BlobClient lockBlob = getBlobClient(blobName);
            if (deleteLockBlobIfUnleased(lockBlob)) {
              log.info("Cleaned up stale unleased Azure lock blob: {}", blobName);
            }
          }
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to cleanup stale Azure locks in container", e);
    }
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
      log.info("Interrupting JVM-local stream for upload ID {}", idStr);
      Utils.interruptStream(streamRef.get());
    }
  }

  /** Creates a .stop signal blob to request remote pods to halt active streaming appends. */
  void createStopSignalBlob(String idStr) {
    try {
      BlobClient stopBlob = containerClient.getBlobClient(locksPrefix + idStr + ".stop");
      stopBlob.upload(BinaryData.fromString("stop"), true);
    } catch (Exception e) {
      log.debug("Failed to write .stop signal blob for upload ID {}: {}", idStr, e.getMessage());
    }
  }

  /** Deletes the .stop signal blob after lock acquisition. */
  void deleteStopSignalBlob(String idStr) {
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

  BlobLeaseClient createBlobLeaseClient(BlobClient lockBlob) {
    return new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
  }

  Iterable<BlobItem> listBlobs(ListBlobsOptions options) {
    return containerClient.listBlobs(options, null);
  }

  BlobClient getBlobClient(String blobName) {
    return containerClient.getBlobClient(blobName);
  }

  /**
   * Periodically polled by {@link #watchdogExecutor} to detect remote .stop signal blobs on Azure
   * and interrupt local active streams.
   */
  void pollStopSignals() {
    if (activeStreams.isEmpty()) {
      return;
    }
    for (String idStr : activeStreams.keySet()) {
      try {
        // Clean up garbage-collected weak references to prevent memory/key leaks
        // in activeStreams map over long-running service lifecycles.
        WeakReference<InterruptibleInputStream> streamRef = activeStreams.get(idStr);
        if (streamRef == null || streamRef.get() == null) {
          activeStreams.remove(idStr);
          continue;
        }
        BlobClient stopBlob = containerClient.getBlobClient(locksPrefix + idStr + ".stop");
        if (Boolean.TRUE.equals(stopBlob.exists())) {
          log.info("Detected remote .stop signal blob for upload ID {}", idStr);
          interruptLocalStream(idStr);
          stopBlob.deleteIfExists();
        }
      } catch (Exception e) {
        log.debug("Error in azure-lock-watchdog polling execution: {}", e.getMessage());
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
