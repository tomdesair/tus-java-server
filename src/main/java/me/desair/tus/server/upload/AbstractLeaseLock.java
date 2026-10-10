package me.desair.tus.server.upload;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
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
 *   <li>Background heartbeat lease auto-renewal daemon scheduling.
 *   <li>Active request input stream registration tracking and clean shutdown.
 * </ul>
 */
public abstract class AbstractLeaseLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(AbstractLeaseLock.class);

  private final LeaseData leaseData;
  private ScheduledExecutorService heartbeatExecutor;
  private final Map<String, InputStream> activeInputStreams;

  /**
   * Constructs an active lock and schedules the background heartbeat renewal daemon.
   *
   * @param leaseData The lease metadata
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param watchdogThreadName Name prefix for the watchdog heartbeat thread
   */
  protected AbstractLeaseLock(
      LeaseData leaseData, Map<String, InputStream> activeInputStreams, String watchdogThreadName) {
    this(leaseData, activeInputStreams, null, watchdogThreadName);
  }

  /**
   * Full constructor allowing injection of a custom executor (e.g. for testing).
   *
   * @param leaseData The lease metadata
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param heartbeatExecutor ScheduledExecutorService for lease renewal, or null to auto-schedule
   * @param watchdogThreadName Name prefix for the watchdog heartbeat thread
   */
  protected AbstractLeaseLock(
      LeaseData leaseData,
      Map<String, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor,
      String watchdogThreadName) {
    this.leaseData = Objects.requireNonNull(leaseData, "leaseData must not be null");
    this.activeInputStreams = activeInputStreams;

    long leaseDurationMs = leaseData.getLeaseDurationMs();
    if (heartbeatExecutor != null) {
      this.heartbeatExecutor = heartbeatExecutor;
    } else if (leaseDurationMs > 0 && watchdogThreadName != null) {
      long renewalPeriodMs = Math.max(1000L, leaseDurationMs / 3);
      this.heartbeatExecutor =
          Utils.scheduleWatchdog(
              watchdogThreadName + "-" + leaseData.getHolderId(),
              this::renewLease,
              renewalPeriodMs,
              renewalPeriodMs,
              TimeUnit.MILLISECONDS);
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
    // 1. Terminate heartbeat daemon cleanly via centralized Utils helper
    Utils.shutdownExecutor(heartbeatExecutor);

    // 2. Remove active stream registration from JVM heap
    if (activeInputStreams != null && getRequestUri() != null) {
      activeInputStreams.remove(getRequestUri());
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
          "Aborting active input stream for upload URI {} as lock was taken over: {}",
          getRequestUri(),
          e.getMessage());
      abortActiveStreamAndHeartbeat();
    } catch (Exception e) {
      leaseData.setExpiresAt(oldExpiresAt);
      // Transient failure (e.g. network timeout). Only abort if the previous lease actually
      // expired.
      if (System.currentTimeMillis() >= oldExpiresAt) {
        log.warn(
            "Lease for upload URI {} expired after renewal failure; aborting active stream",
            getRequestUri(),
            e);
        abortActiveStreamAndHeartbeat();
      } else {
        log.warn(
            "Transient failure renewing lease for upload URI {} (will retry on next tick): {}",
            getRequestUri(),
            e.getMessage());
      }
    }
  }

  private void abortActiveStreamAndHeartbeat() {
    if (activeInputStreams != null && getRequestUri() != null) {
      InputStream stream = activeInputStreams.get(getRequestUri());
      if (stream != null) {
        log.info(
            "Aborting active input stream for upload URI {} due to lease renewal failure",
            getRequestUri());
        Utils.interruptStream(stream);
      }
    }
    Utils.shutdownExecutor(heartbeatExecutor);
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
