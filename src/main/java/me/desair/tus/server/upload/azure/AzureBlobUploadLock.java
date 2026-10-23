package me.desair.tus.server.upload.azure;

import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed upload lock implementation backed by Azure Blob Storage Leases.
 *
 * <p>Azure Blob Leases provide native, atomic distributed locks. This class wraps an active lease
 * and maintains a background daemon executor that periodically renews the lease (every 10 seconds
 * for a standard 30-second lease) to prevent lock expiry during long-running streaming upload
 * operations.
 */
public class AzureBlobUploadLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobUploadLock.class);
  private static final long RENEWAL_INTERVAL_SECONDS = 10L;

  private final BlobLeaseClient leaseClient;
  private final BlobClient lockBlob;
  private final BlobClient stopBlob;
  private final String uploadUri;
  private final String uploadId;
  private final Map<String, WeakReference<InterruptibleInputStream>> activeStreams;
  private final Map<String, AzureBlobUploadLock> activeLocks;
  private final ScheduledFuture<?> renewalTask;
  private final ReadWriteLock thisObjectLock = new ReentrantReadWriteLock();
  private boolean released = false;
  private boolean blobDeleted = false;

  /**
   * Constructs an {@link AzureBlobUploadLock} wrapping an acquired Azure Blob lease.
   *
   * @param leaseClient The pre-acquired {@link BlobLeaseClient} holding the lease
   * @param lockBlob The target lock {@link BlobClient}
   * @param uploadUri The upload URI associated with this lock
   */
  public AzureBlobUploadLock(BlobLeaseClient leaseClient, BlobClient lockBlob, String uploadUri) {
    this(
        Objects.requireNonNull(leaseClient, "leaseClient must not be null"),
        Objects.requireNonNull(lockBlob, "lockBlob must not be null"),
        uploadUri,
        null,
        null,
        null,
        null);
  }

  /**
   * Full constructor for active lock with stream registration and lease tracking.
   *
   * @param leaseClient The pre-acquired {@link BlobLeaseClient} holding the lease
   * @param lockBlob The target lock {@link BlobClient}
   * @param uploadUri The upload URI associated with this lock
   * @param uploadId The upload ID associated with this lock
   * @param activeStreams JVM-wide map of active input streams
   */
  public AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams) {
    this(
        Objects.requireNonNull(leaseClient, "leaseClient must not be null"),
        Objects.requireNonNull(lockBlob, "lockBlob must not be null"),
        uploadUri,
        null,
        uploadId,
        activeStreams,
        null);
  }

  /**
   * Full constructor for active lock with stream registration, lease tracking, and active lock map.
   *
   * @param leaseClient The pre-acquired {@link BlobLeaseClient} holding the lease
   * @param lockBlob The target lock {@link BlobClient}
   * @param uploadUri The upload URI associated with this lock
   * @param uploadId The upload ID associated with this lock
   * @param activeStreams JVM-wide map of active input streams
   * @param activeLocks Map of currently active locks in the locking service
   */
  public AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams,
      Map<String, AzureBlobUploadLock> activeLocks) {
    this(
        Objects.requireNonNull(leaseClient, "leaseClient must not be null"),
        Objects.requireNonNull(lockBlob, "lockBlob must not be null"),
        uploadUri,
        null,
        uploadId,
        activeStreams,
        activeLocks);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor) {
    this(leaseClient, lockBlob, uploadUri, renewalExecutor, null, null, null);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams) {
    this(leaseClient, lockBlob, uploadUri, renewalExecutor, uploadId, activeStreams, null);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams,
      Map<String, AzureBlobUploadLock> activeLocks) {
    this(
        leaseClient,
        lockBlob,
        null,
        uploadUri,
        renewalExecutor,
        uploadId,
        activeStreams,
        activeLocks);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      BlobClient stopBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams,
      Map<String, AzureBlobUploadLock> activeLocks) {
    this.leaseClient = leaseClient;
    this.lockBlob = lockBlob;
    this.stopBlob = stopBlob;
    this.uploadUri = Objects.requireNonNull(uploadUri, "uploadUri must not be null");
    this.uploadId = uploadId;
    this.activeStreams = activeStreams;
    this.activeLocks = activeLocks;

    if (renewalExecutor != null) {
      this.renewalTask =
          renewalExecutor.scheduleWithFixedDelay(
              this::renewLease,
              RENEWAL_INTERVAL_SECONDS,
              RENEWAL_INTERVAL_SECONDS,
              TimeUnit.SECONDS);
    } else {
      this.renewalTask = null;
    }
  }

  // Azure Blob leases have a 30-second duration on the server. We track local expiration as
  // 28 seconds (30s minus a 2s clock-drift safety margin) so that if connectivity to Azure is lost
  // or renewal requests fail, we treat the lease as expired before Azure expires the lease on the
  // server and grants it to another contender, preventing split-brain concurrent writes.
  private static final long LEASE_DURATION_MS = 28000L;
  private long leaseExpiresAt = System.currentTimeMillis() + LEASE_DURATION_MS;

  BlobLeaseClient getLeaseClient() {
    return leaseClient;
  }

  void markBlobDeleted() {
    thisObjectLock.writeLock().lock();
    try {
      this.blobDeleted = true;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
  }

  boolean isBlobDeleted() {
    thisObjectLock.readLock().lock();
    try {
      return blobDeleted;
    } finally {
      thisObjectLock.readLock().unlock();
    }
  }

  void setLeaseExpiresAt(long leaseExpiresAt) {
    thisObjectLock.writeLock().lock();
    try {
      this.leaseExpiresAt = leaseExpiresAt;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
  }

  /** Attempts to renew the lease with Azure Blob Storage. */
  void renewLease() {
    thisObjectLock.readLock().lock();
    try {
      if (released) {
        return;
      }
    } finally {
      thisObjectLock.readLock().unlock();
    }
    try {
      executeRenew();
      thisObjectLock.writeLock().lock();
      try {
        leaseExpiresAt = System.currentTimeMillis() + LEASE_DURATION_MS;
      } finally {
        thisObjectLock.writeLock().unlock();
      }
      log.trace("Successfully renewed Azure blob lease for upload URI {}", uploadUri);
    } catch (Exception e) {
      boolean leaseLost = false;
      if (e instanceof BlobStorageException) {
        BlobStorageException bse = (BlobStorageException) e;
        AzureErrorType errorType = AzureUtils.parseErrorResponse(bse);
        if (errorType == AzureErrorType.LEASE_ALREADY_PRESENT
            || errorType == AzureErrorType.LEASE_NOT_PRESENT
            || errorType == AzureErrorType.CONFLICT
            || bse.getStatusCode() == 409
            || bse.getStatusCode() == 412) {
          leaseLost = true;
        }
      }

      boolean expired;
      thisObjectLock.readLock().lock();
      try {
        expired = System.currentTimeMillis() >= leaseExpiresAt;
      } finally {
        thisObjectLock.readLock().unlock();
      }

      if (leaseLost || expired) {
        log.warn(
            "Azure blob lease definitively lost/expired for upload URI {}: {}",
            uploadUri,
            e.getMessage());
        thisObjectLock.writeLock().lock();
        try {
          released = true;
        } finally {
          thisObjectLock.writeLock().unlock();
        }
        cancelRenewalTask();
        // If lease renewal fails definitively, abort any active input stream immediately
        // so the upload thread does not continue writing un-locked data to Azure.
        if (activeStreams != null && uploadId != null) {
          WeakReference<InterruptibleInputStream> streamRef = activeStreams.remove(uploadId);
          if (streamRef != null) {
            log.info(
                "Aborting active stream for upload ID {} due to lease renewal failure", uploadId);
            Utils.interruptStream(streamRef.get());
          }
        }
      } else {
        log.warn(
            "Transient failure renewing Azure blob lease for upload URI {} (will retry on next tick):"
                + " {}",
            uploadUri,
            e.getMessage());
      }
    }
  }

  /** Performs the actual lease renewal network call against Azure SDK. */
  void executeRenew() {
    if (leaseClient != null) {
      leaseClient.renewLease();
    }
  }

  @Override
  public void release() {
    thisObjectLock.writeLock().lock();
    try {
      if (released) {
        return;
      }
      released = true;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
    cancelRenewalTask();
    // Remove active stream and lock registrations from service maps upon release
    // to avoid stale references lingering in heap.
    if (activeStreams != null && uploadId != null) {
      activeStreams.remove(uploadId);
    }
    if (activeLocks != null && uploadId != null) {
      activeLocks.remove(uploadId);
    }
    boolean alreadyDeleted;
    thisObjectLock.readLock().lock();
    try {
      alreadyDeleted = blobDeleted;
    } finally {
      thisObjectLock.readLock().unlock();
    }

    // Atomically delete the lock blob using the active lease ID. This guarantees that:
    // 1. The lock blob is cleaned up immediately with a single HTTP round-trip (no leftover locks).
    // 2. We are fenced against stale takeovers via Azure server-side condition (412
    // LeaseIdMismatch).
    // 3. If deletion fails for any reason, we fall back to releasing the lease cleanly.
    if (leaseClient != null && !alreadyDeleted && lockBlob != null) {
      try {
        lockBlob.deleteWithResponse(
            DeleteSnapshotsOptionType.INCLUDE,
            new BlobRequestConditions().setLeaseId(leaseClient.getLeaseId()),
            null,
            Context.NONE);
        log.trace("Deleted Azure lock blob for upload URI {}", uploadUri);
      } catch (BlobStorageException e) {
        if (e.getStatusCode() != 404) {
          log.debug(
              "Azure lock blob delete failed (falling back to releaseLease) for URI {}: {}",
              uploadUri,
              e.getMessage());
          fallbackReleaseLease();
        }
      } catch (Exception e) {
        log.debug(
            "Azure lock blob delete failed (falling back to releaseLease) for URI {}: {}",
            uploadUri,
            e.getMessage());
        fallbackReleaseLease();
      }
    }

    AzureUtils.deleteBlobQuietly(stopBlob);
  }

  void fallbackReleaseLease() {
    try {
      executeReleaseLease();
    } catch (Exception ex) {
      log.debug("Fallback releaseLease failed for URI {}: {}", uploadUri, ex.getMessage());
    }
  }

  /** Performs the actual lease release network call against Azure SDK. */
  void executeReleaseLease() {
    if (leaseClient != null) {
      leaseClient.releaseLease();
    }
  }

  @Override
  public void close() throws IOException {
    release();
  }

  @Override
  public String getUploadUri() {
    return uploadUri;
  }

  /** Cancels the periodic lease renewal task. */
  private void cancelRenewalTask() {
    if (renewalTask != null) {
      renewalTask.cancel(true);
    }
  }
}
