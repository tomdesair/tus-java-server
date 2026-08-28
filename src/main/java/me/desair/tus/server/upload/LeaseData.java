package me.desair.tus.server.upload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.Serializable;
import java.util.Objects;

/**
 * Serializable data transfer object representing the on-disk or cloud JSON metadata of an upload
 * lock lease.
 *
 * <p>Contains the lease holder identifier, target request URI, duration, acquisition timestamp,
 * expiration timestamp, lock resource path/key, and contention stop signal path/key.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LeaseData implements Serializable {

  private static final long serialVersionUID = 1L;

  private String holderId;
  private String requestUri;
  private long leaseDurationMs;
  private long acquiredAt;
  private long expiresAt;
  private String lockPath;
  private String stopPath;

  /** Default constructor for Jackson JSON deserialization. */
  public LeaseData() {}

  /**
   * Convenience constructor initializing timestamps automatically.
   *
   * @param holderId Unique identifier of the lock holder
   * @param requestUri Target upload URI
   * @param leaseDurationMs Lease duration in milliseconds
   * @param expiresAt Timestamp when lease expires
   */
  public LeaseData(String holderId, String requestUri, long leaseDurationMs, long expiresAt) {
    this(holderId, requestUri, leaseDurationMs, expiresAt, System.currentTimeMillis(), null, null);
  }

  /**
   * Full constructor.
   *
   * @param holderId Unique identifier of the lock holder
   * @param requestUri Target upload URI
   * @param leaseDurationMs Lease duration in milliseconds
   * @param expiresAt Timestamp when lease expires
   * @param acquiredAt Timestamp when lease was acquired
   * @param lockPath Storage path or S3 key of the lock
   * @param stopPath Contention stop signal path or S3 key
   */
  public LeaseData(
      String holderId,
      String requestUri,
      long leaseDurationMs,
      long expiresAt,
      long acquiredAt,
      String lockPath,
      String stopPath) {
    this.holderId = holderId;
    this.requestUri = requestUri;
    this.leaseDurationMs = leaseDurationMs;
    this.expiresAt = expiresAt;
    this.acquiredAt = acquiredAt;
    this.lockPath = lockPath;
    this.stopPath = stopPath;
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

  public long getAcquiredAt() {
    return acquiredAt;
  }

  public void setAcquiredAt(long acquiredAt) {
    this.acquiredAt = acquiredAt;
  }

  public long getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(long expiresAt) {
    this.expiresAt = expiresAt;
  }

  public String getLockPath() {
    return lockPath;
  }

  public void setLockPath(String lockPath) {
    this.lockPath = lockPath;
  }

  public String getStopPath() {
    return stopPath;
  }

  public void setStopPath(String stopPath) {
    this.stopPath = stopPath;
  }

  public boolean isExpired(long now) {
    return expiresAt < now;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LeaseData that)) {
      return false;
    }
    return leaseDurationMs == that.leaseDurationMs
        && acquiredAt == that.acquiredAt
        && expiresAt == that.expiresAt
        && Objects.equals(holderId, that.holderId)
        && Objects.equals(requestUri, that.requestUri)
        && Objects.equals(lockPath, that.lockPath)
        && Objects.equals(stopPath, that.stopPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        holderId, requestUri, leaseDurationMs, acquiredAt, expiresAt, lockPath, stopPath);
  }
}
