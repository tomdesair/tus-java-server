package me.desair.tus.server.upload;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for distributed lease-based implementations of {@link UploadLock}.
 *
 * <p>Provides common lifecycle management for TTL-based locks including:
 *
 * <ul>
 *   <li>Background heartbeat lease auto-renewal task execution via a shared service executor.
 *   <li>Active request input stream registration tracking and clean shutdown.
 * </ul>
 */
public abstract class AbstractLeaseLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(AbstractLeaseLock.class);

  private final LeaseData leaseData;
  private final UploadId uploadId;
  private final Map<UploadId, InputStream> activeInputStreams;
  private final ScheduledFuture<?> heartbeatTask;

  /**
   * Constructs an active lock and schedules the background heartbeat renewal task using the shared
   * service-level executor.
   *
   * @param leaseData The lease metadata
   * @param uploadId The upload identifier associated with this lock
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param heartbeatExecutor Shared ScheduledExecutorService for lease renewal
   */
  protected AbstractLeaseLock(
      LeaseData leaseData,
      UploadId uploadId,
      Map<UploadId, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor) {
    this.leaseData = Objects.requireNonNull(leaseData, "leaseData must not be null");
    this.uploadId = uploadId;
    this.activeInputStreams = activeInputStreams;

    long leaseDurationMs = leaseData.getLeaseDurationMs();
    if (heartbeatExecutor != null && leaseDurationMs > 0) {
      long renewalPeriodMs = Math.max(1000L, leaseDurationMs / 3);
      this.heartbeatTask =
          heartbeatExecutor.scheduleWithFixedDelay(
              this::renewLease, renewalPeriodMs, renewalPeriodMs, TimeUnit.MILLISECONDS);
    } else {
      this.heartbeatTask = null;
    }
  }

  public LeaseData getLeaseData() {
    return leaseData;
  }

  public String getHolderId() {
    return leaseData.getHolderId();
  }

  public String getRequestUri() {
    return leaseData.getRequestUri();
  }

  public UploadId getUploadId() {
    return uploadId;
  }

  public long getLeaseDurationMs() {
    return leaseData.getLeaseDurationMs();
  }

  public long getExpiresAt() {
    return leaseData.getExpiresAt();
  }

  public void setExpiresAt(long expiresAt) {
    leaseData.setExpiresAt(expiresAt);
  }

  public long getAcquiredAt() {
    return leaseData.getAcquiredAt();
  }

  @Override
  public String getUploadUri() {
    return leaseData.getRequestUri();
  }

  @Override
  public void release() {
    close();
  }

  @Override
  public void close() {
    // 1. Cancel heartbeat task cleanly
    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }

    // 2. Remove active stream registration from JVM heap
    if (activeInputStreams != null && uploadId != null) {
      activeInputStreams.remove(uploadId);
    }

    // 3. Release backend storage resources
    releaseLockResource();
  }

  /**
   * Renew the lock lease by advancing the expiration timestamp and persisting the updated metadata.
   */
  public void renewLease() {
    long oldExpiresAt = leaseData.getExpiresAt();
    long candidateExpiresAt = System.currentTimeMillis() + leaseData.getLeaseDurationMs();
    try {
      leaseData.setExpiresAt(candidateExpiresAt);
      doRenewLease();
    } catch (IllegalStateException e) {
      leaseData.setExpiresAt(oldExpiresAt);
      // Ownership lost (e.g. lease taken over by another node): abort immediately
      log.info(
          "Aborting active input stream for upload ID {} as lock was taken over: {}",
          uploadId,
          e.getMessage());
      abortActiveStreamAndHeartbeat();
    } catch (Exception e) {
      leaseData.setExpiresAt(oldExpiresAt);
      // Transient failure (e.g. network timeout). Only abort if previous lease actually expired.
      if (System.currentTimeMillis() >= oldExpiresAt) {
        log.warn(
            "Lease for upload ID {} expired after renewal failure; aborting active stream",
            uploadId,
            e);
        abortActiveStreamAndHeartbeat();
      } else {
        log.warn(
            "Transient failure renewing lease for upload ID {} (will retry on next tick): {}",
            uploadId,
            e.getMessage());
      }
    }
  }

  private void abortActiveStreamAndHeartbeat() {
    if (activeInputStreams != null && uploadId != null) {
      InputStream stream = activeInputStreams.remove(uploadId);
      if (stream != null) {
        log.info(
            "Aborting active input stream for upload ID {} due to lease renewal failure", uploadId);
        Utils.interruptStream(stream);
      }
    }
    if (heartbeatTask != null) {
      heartbeatTask.cancel(true);
    }
  }

  /**
   * Subclass hook to persist updated lease metadata during heartbeat renewal.
   *
   * @throws Exception If lease renewal fails or ownership was lost
   */
  protected abstract void doRenewLease() throws Exception;

  /**
   * Subclass hook to release backend-specific lock resources (e.g. delete lock files or S3
   * objects).
   */
  protected abstract void releaseLockResource();
}
