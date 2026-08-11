package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.upload.UploadLock;
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
    this.leaseClient = Objects.requireNonNull(leaseClient, "leaseClient must not be null");
    this.lockBlob = Objects.requireNonNull(lockBlob, "lockBlob must not be null");
    this.uploadUri = Objects.requireNonNull(uploadUri, "uploadUri must not be null");

    // Initialize background daemon thread to renew lease periodically during upload
    this.renewalExecutor =
        Utils.scheduleWatchdog(
            "azure-lease-renewal-" + uploadUri,
            this::renewLease,
            RENEWAL_INTERVAL_SECONDS,
            RENEWAL_INTERVAL_SECONDS,
            TimeUnit.SECONDS);
  }

  /** Attempts to renew the lease with Azure Blob Storage. */
  private void renewLease() {
    if (released) {
      return;
    }
    try {
      leaseClient.renewLease();
      log.trace("Successfully renewed Azure blob lease for upload URI {}", uploadUri);
    } catch (Exception e) {
      log.warn("Failed to renew Azure blob lease for upload URI {}: {}", uploadUri, e.getMessage());
      // ponytail: lease was broken externally or expired, shutdown executor
      released = true;
      shutdownExecutor();
    }
  }

  @Override
  public void release() {
    if (!released) {
      released = true;
      shutdownExecutor();
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
