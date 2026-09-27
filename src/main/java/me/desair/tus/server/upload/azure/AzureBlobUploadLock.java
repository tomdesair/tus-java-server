package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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
  private final String uploadUri;
  private final String uploadId;
  private final Map<String, WeakReference<InterruptibleInputStream>> activeStreams;
  private final ScheduledExecutorService renewalExecutor;
  private volatile boolean released = false;

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
        activeStreams);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor) {
    this(leaseClient, lockBlob, uploadUri, renewalExecutor, null, null);
  }

  AzureBlobUploadLock(
      BlobLeaseClient leaseClient,
      BlobClient lockBlob,
      String uploadUri,
      ScheduledExecutorService renewalExecutor,
      String uploadId,
      Map<String, WeakReference<InterruptibleInputStream>> activeStreams) {
    this.leaseClient = leaseClient;
    this.lockBlob = lockBlob;
    this.uploadUri = Objects.requireNonNull(uploadUri, "uploadUri must not be null");
    this.uploadId = uploadId;
    this.activeStreams = activeStreams;

    if (renewalExecutor != null) {
      this.renewalExecutor = renewalExecutor;
    } else if (leaseClient != null) {
      // Initialize background daemon thread to renew lease periodically during upload
      this.renewalExecutor =
          Utils.scheduleWatchdog(
              "azure-lease-renewal-" + uploadUri,
              this::renewLease,
              RENEWAL_INTERVAL_SECONDS,
              RENEWAL_INTERVAL_SECONDS,
              TimeUnit.SECONDS);
    } else {
      this.renewalExecutor = null;
    }
  }

  BlobLeaseClient getLeaseClient() {
    return leaseClient;
  }

  /** Attempts to renew the lease with Azure Blob Storage. */
  void renewLease() {
    if (released) {
      return;
    }
    try {
      executeRenew();
      log.trace("Successfully renewed Azure blob lease for upload URI {}", uploadUri);
    } catch (Exception e) {
      log.warn("Failed to renew Azure blob lease for upload URI {}: {}", uploadUri, e.getMessage());
      released = true;
      shutdownExecutor();
      // If lease renewal fails, abort any active input stream immediately
      // so the upload thread does not continue writing un-locked data to Azure.
      if (activeStreams != null && uploadId != null) {
        WeakReference<InterruptibleInputStream> streamRef = activeStreams.remove(uploadId);
        if (streamRef != null) {
          log.info(
              "Aborting active stream for upload ID {} due to lease renewal failure", uploadId);
          Utils.interruptStream(streamRef.get());
        }
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
    if (!released) {
      released = true;
      shutdownExecutor();
      // Remove active stream registration from JVM map upon release
      // to avoid stale references lingering in heap.
      if (activeStreams != null && uploadId != null) {
        activeStreams.remove(uploadId);
      }
      if (leaseClient != null) {
        try {
          leaseClient.releaseLease();
          log.trace("Released Azure blob lease for upload URI {}", uploadUri);
        } catch (Exception e) {
          log.debug(
              "Azure blob lease release failed (may have already expired/broken) for URI {}: {}",
              uploadUri,
              e.getMessage());
        }
      }
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

  /** Shuts down the renewal executor cleanly. */
  private void shutdownExecutor() {
    Utils.shutdownExecutor(renewalExecutor);
  }
}
