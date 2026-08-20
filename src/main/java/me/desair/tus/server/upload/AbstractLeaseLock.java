package me.desair.tus.server.upload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for distributed lease-based implementations of {@link UploadLock}.
 *
 * <p>Provides common state and lifecycle management for TTL-based locks including:
 *
 * <ul>
 *   <li>Lease holder identification, expiration timestamps, and target upload URIs.
 *   <li>Background heartbeat lease auto-renewal daemon scheduling.
 *   <li>Active request input stream registration tracking and clean shutdown.
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class AbstractLeaseLock implements UploadLock {

  private static final Logger log = LoggerFactory.getLogger(AbstractLeaseLock.class);

  private String holderId;
  private String requestUri;
  private long leaseDurationMs;
  private long expiresAt;
  private long acquiredAt;

  @JsonIgnore private ScheduledExecutorService heartbeatExecutor;
  @JsonIgnore private Map<String, InputStream> activeInputStreams;

  /** Default constructor for Jackson JSON deserialization. */
  protected AbstractLeaseLock() {}

  /**
   * Base constructor for metadata serialization.
   *
   * @param holderId Unique identifier of the lock holder
   * @param requestUri Target upload URI
   * @param leaseDurationMs Lease duration in milliseconds
   * @param expiresAt Absolute expiration epoch timestamp in milliseconds
   */
  protected AbstractLeaseLock(
      String holderId, String requestUri, long leaseDurationMs, long expiresAt) {
    this.holderId = holderId;
    this.requestUri = requestUri;
    this.leaseDurationMs = leaseDurationMs;
    this.expiresAt = expiresAt;
    this.acquiredAt = System.currentTimeMillis();
  }

  /**
   * Constructs an active lock and schedules the background heartbeat renewal daemon.
   *
   * @param holderId Unique identifier of the lock holder
   * @param leaseDurationMs Lease duration in milliseconds
   * @param requestUri Target upload URI
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param watchdogThreadName Name prefix for the watchdog heartbeat thread
   */
  protected AbstractLeaseLock(
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> activeInputStreams,
      String watchdogThreadName) {
    this(holderId, leaseDurationMs, requestUri, activeInputStreams, null, watchdogThreadName);
  }

  /**
   * Full constructor allowing injection of a custom executor (e.g. for testing).
   *
   * @param holderId Unique identifier of the lock holder
   * @param leaseDurationMs Lease duration in milliseconds
   * @param requestUri Target upload URI
   * @param activeInputStreams Map of active request input streams in the JVM
   * @param heartbeatExecutor ScheduledExecutorService for lease renewal, or null to auto-schedule
   * @param watchdogThreadName Name prefix for the watchdog heartbeat thread
   */
  protected AbstractLeaseLock(
      String holderId,
      long leaseDurationMs,
      String requestUri,
      Map<String, InputStream> activeInputStreams,
      ScheduledExecutorService heartbeatExecutor,
      String watchdogThreadName) {
    this.holderId = holderId;
    this.leaseDurationMs = leaseDurationMs;
    this.requestUri = requestUri;
    this.activeInputStreams = activeInputStreams;
    this.acquiredAt = System.currentTimeMillis();
    this.expiresAt = this.acquiredAt + leaseDurationMs;

    if (heartbeatExecutor != null) {
      this.heartbeatExecutor = heartbeatExecutor;
    } else if (leaseDurationMs > 0 && watchdogThreadName != null) {
      long renewalPeriodMs = Math.max(1000L, leaseDurationMs / 3);
      this.heartbeatExecutor =
          Utils.scheduleWatchdog(
              watchdogThreadName + "-" + holderId,
              this::renewLease,
              renewalPeriodMs,
              renewalPeriodMs,
              TimeUnit.MILLISECONDS);
    }
  }

  public String getHolderId() {
    return holderId;
  }

  public void setHolderId(String holderId) {
    this.holderId = holderId;
  }

  public String getRequestUri() {
    return requestUri;
  }

  public void setRequestUri(String requestUri) {
    this.requestUri = requestUri;
  }

  public long getLeaseDurationMs() {
    return leaseDurationMs;
  }

  public void setLeaseDurationMs(long leaseDurationMs) {
    this.leaseDurationMs = leaseDurationMs;
  }

  public long getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(long expiresAt) {
    this.expiresAt = expiresAt;
  }

  public long getAcquiredAt() {
    return acquiredAt;
  }

  public void setAcquiredAt(long acquiredAt) {
    this.acquiredAt = acquiredAt;
  }

  @Override
  public String getUploadUri() {
    return requestUri;
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
    if (activeInputStreams != null && requestUri != null) {
      activeInputStreams.remove(requestUri);
    }

    // 3. Release backend storage resources
    releaseLockResource();
  }

  /**
   * Renew the lock lease by advancing the expiration timestamp and persisting the updated metadata.
   */
  public void renewLease() {
    this.expiresAt = System.currentTimeMillis() + leaseDurationMs;
    doRenewLease();
  }

  /** Subclass hook to persist updated lease metadata during heartbeat renewal. */
  protected abstract void doRenewLease();

  /**
   * Subclass hook to release backend-specific lock resources (e.g. delete lock files or S3
   * objects).
   */
  protected abstract void releaseLockResource();
}
