package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.checksum.DeduplicationService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.disk.DiskUtils;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base implementation of {@link UploadStorageService} providing shared configuration
 * storage, default accessors, lifecycle hooks, and common upload retrieval operations.
 */
public abstract class AbstractUploadStorageService implements UploadStorageService, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(AbstractUploadStorageService.class);

  protected Long maxUploadSize = null;
  protected Long maxAppendSize = null;
  protected Long minAppendSize = null;
  protected Long minSize = null;
  protected Long uploadExpirationPeriod = null;
  protected boolean uploadDeduplicationEnabled = false;
  protected UploadLockingService uploadLockingService = null;
  protected UploadConcatenationService concatenationService = null;
  protected UploadIdFactory idFactory = new TimeBasedUploadIdFactory();
  protected boolean jsonSerializationEnabled = true;
  protected Duration drainTimeout = Duration.ofSeconds(10);
  protected int cloudUploadThreadPoolSize = 10;
  protected ThreadPoolExecutor uploadExecutor = null;

  @Override
  public void setMaxUploadSize(Long maxUploadSize) {
    this.maxUploadSize = (maxUploadSize != null && maxUploadSize > 0 ? maxUploadSize : 0L);
  }

  @Override
  public long getMaxUploadSize() {
    return maxUploadSize != null ? maxUploadSize : 0L;
  }

  @Override
  public void setMaxAppendSize(Long maxAppendSize) {
    this.maxAppendSize = (maxAppendSize != null && maxAppendSize > 0 ? maxAppendSize : null);
  }

  @Override
  public Long getMaxAppendSize() {
    if (maxAppendSize != null && maxAppendSize > 0) {
      return maxAppendSize;
    }
    long maxUpload = getMaxUploadSize();
    return maxUpload > 0 ? maxUpload : null;
  }

  @Override
  public void setMinAppendSize(Long minAppendSize) {
    this.minAppendSize = (minAppendSize != null && minAppendSize > 0 ? minAppendSize : null);
  }

  @Override
  public Long getMinAppendSize() {
    return minAppendSize;
  }

  @Override
  public void setMinSize(Long minSize) {
    this.minSize = (minSize != null && minSize > 0 ? minSize : null);
  }

  @Override
  public Long getMinSize() {
    return minSize;
  }

  @Override
  public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {
    this.uploadExpirationPeriod = uploadExpirationPeriod;
  }

  @Override
  public Long getUploadExpirationPeriod() {
    return uploadExpirationPeriod;
  }

  @Override
  public void setUploadDeduplicationEnabled(boolean enabled) {
    this.uploadDeduplicationEnabled = enabled;
  }

  @Override
  public boolean isUploadDeduplicationEnabled() {
    return this.uploadDeduplicationEnabled;
  }

  @Override
  public void setUploadConcatenationService(UploadConcatenationService concatenationService) {
    this.concatenationService = concatenationService;
  }

  @Override
  public UploadConcatenationService getUploadConcatenationService() {
    return this.concatenationService;
  }

  @Override
  public void setUploadLockingService(UploadLockingService uploadLockingService) {
    if (this.uploadLockingService != null && this.uploadLockingService != uploadLockingService) {
      try {
        this.uploadLockingService.close();
      } catch (IOException e) {
        log.warn("Error closing replaced upload locking service: {}", e.getMessage());
      }
    }
    this.uploadLockingService =
        Objects.requireNonNull(uploadLockingService, "uploadLockingService must not be null");
    DeduplicationService deduplicationService = getDeduplicationService();
    if (deduplicationService != null) {
      deduplicationService.setUploadLockingService(uploadLockingService);
    }
  }

  @Override
  public UploadLockingService getUploadLockingService() {
    return this.uploadLockingService;
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    if (idFactory != null) {
      this.idFactory = idFactory;
    }
  }

  /**
   * Returns the current {@link UploadIdFactory}.
   *
   * @return The configured upload ID factory
   */
  public UploadIdFactory getIdFactory() {
    return this.idFactory;
  }

  @Override
  public void setJsonSerializationEnabled(boolean enabled) {
    this.jsonSerializationEnabled = enabled;
  }

  @Override
  public boolean isJsonSerializationEnabled() {
    return this.jsonSerializationEnabled;
  }

  @Override
  public void setDrainTimeout(Duration drainTimeout) {
    if (drainTimeout != null) {
      this.drainTimeout = drainTimeout;
    }
  }

  @Override
  public Duration getDrainTimeout() {
    return this.drainTimeout;
  }

  @Override
  public void copyUploadTo(UploadInfo uploadInfo, OutputStream outputStream)
      throws UploadNotFoundException, IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    Objects.requireNonNull(outputStream, "OutputStream must not be null");

    try (InputStream is = getUploadedBytes(uploadInfo.getId())) {
      if (is == null) {
        throw new UploadNotFoundException("Upload data not found for ID " + uploadInfo.getId());
      }
      IOUtils.copyLarge(is, outputStream);
    }
  }

  @Override
  public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
    UploadInfo uploadInfo = getUploadInfo(idFactory.readUploadId(uploadUrl));
    if (uploadInfo == null || !Objects.equals(uploadInfo.getOwnerKey(), ownerKey)) {
      return null;
    }
    return uploadInfo;
  }

  @Override
  public UploadInfo deduplicate(UploadInfo uploadInfo, ChecksumKey checksumKey) throws IOException {
    if (!isUploadDeduplicationEnabled() || uploadInfo == null || checksumKey == null) {
      return uploadInfo;
    }
    DeduplicationService deduplicationService = getDeduplicationService();
    return deduplicationService != null
        ? deduplicationService.deduplicate(uploadInfo, checksumKey)
        : uploadInfo;
  }

  @Override
  public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    if (!isUploadDeduplicationEnabled() || checksum == null || algorithm == null) {
      return null;
    }
    String normalized = DiskUtils.normalizeChecksum(checksum);
    if (!DiskUtils.isSafePathComponent(normalized)
        || !DiskUtils.isSafePathComponent(algorithm.toString())) {
      throw new IOException("The checksum contains an unsafe value.");
    }
    ChecksumKey key;
    try {
      key = ChecksumKey.of(normalized, algorithm);
    } catch (IllegalArgumentException e) {
      return null;
    }
    DeduplicationService deduplicationService = getDeduplicationService();
    return deduplicationService != null ? deduplicationService.getUploadInfoByChecksum(key) : null;
  }

  @Override
  public void setCloudUploadThreadPoolSize(int size) {
    if (size <= 0) {
      throw new IllegalArgumentException(
          "The cloud upload thread pool size must be greater than 0");
    }
    this.cloudUploadThreadPoolSize = size;
    if (this.uploadExecutor != null) {
      Utils.resizeThreadPoolExecutor(this.uploadExecutor, size);
    }
  }

  @Override
  public int getCloudUploadThreadPoolSize() {
    return this.cloudUploadThreadPoolSize;
  }

  public ThreadPoolExecutor getUploadExecutor() {
    return this.uploadExecutor;
  }

  @Override
  public void close() throws IOException {
    if (this.uploadExecutor != null) {
      Utils.shutdownExecutor(this.uploadExecutor, Duration.ofSeconds(5));
    }
  }

  /**
   * Returns the deduplication service instance if configured, or {@code null} if deduplication is
   * unsupported.
   *
   * @return The {@link DeduplicationService} or {@code null}
   */
  protected abstract DeduplicationService getDeduplicationService();
}
