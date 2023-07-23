package me.desair.tus.server.upload.cache;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Objects;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;

/**
 * Combined implementation of {@link UploadStorageService} and {@link UploadLockingService}. Uses
 * both of them as delegates but allowing to reduce disk operations during a request processing by
 * caching UploadInfo in the memory. UploadLockingService service is used as a delegate to cleanup
 * cached data on releasing a lock.
 */
public class ThreadLocalCachedStorageAndLockingService
    implements UploadLockingService, UploadStorageService {

  private final ThreadLocal<WeakReference<UploadInfo>> uploadInfoCache = new ThreadLocal<>();
  private final UploadLockingService lockingServiceDelegate;
  private final UploadStorageService storageServiceDelegate;
  private UploadIdFactory idFactory;

  /** Constructor of ThreadLocalCachedStorageAndLockingService. */
  public ThreadLocalCachedStorageAndLockingService(
      UploadStorageService storageServiceDelegate, UploadLockingService lockingServiceDelegate) {
    if (storageServiceDelegate.getClass() == ThreadLocalCachedStorageAndLockingService.class) {
      this.storageServiceDelegate =
          ((ThreadLocalCachedStorageAndLockingService) storageServiceDelegate)
              .storageServiceDelegate;
    } else {
      this.storageServiceDelegate = storageServiceDelegate;
    }
    if (lockingServiceDelegate.getClass() == ThreadLocalCachedStorageAndLockingService.class) {
      this.lockingServiceDelegate =
          ((ThreadLocalCachedStorageAndLockingService) lockingServiceDelegate)
              .lockingServiceDelegate;
    } else {
      this.lockingServiceDelegate = lockingServiceDelegate;
    }
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    UploadInfo uploadInfo;
    WeakReference<UploadInfo> ref = uploadInfoCache.get();
    if (ref == null || (uploadInfo = ref.get()) == null || !id.equals(uploadInfo.getId())) {
      uploadInfo = storageServiceDelegate.getUploadInfo(id);
      uploadInfoCache.set(new WeakReference<>(uploadInfo));
    }
    return uploadInfo;
  }

  @Override
  public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
    UploadInfo uploadInfo = getUploadInfo(idFactory.readUploadId(uploadUrl));
    if (uploadInfo == null || !Objects.equals(uploadInfo.getOwnerKey(), ownerKey)) {
      uploadInfo = storageServiceDelegate.getUploadInfo(uploadUrl, ownerKey);
      uploadInfoCache.set(new WeakReference<>(uploadInfo));
    }
    return uploadInfo;
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    storageServiceDelegate.update(uploadInfo);
    uploadInfoCache.set(new WeakReference<>(uploadInfo));
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    this.idFactory = idFactory;
    this.storageServiceDelegate.setIdFactory(idFactory);
    this.lockingServiceDelegate.setIdFactory(idFactory);
  }

  @Override
  public String getUploadUri() {
    return storageServiceDelegate.getUploadUri();
  }

  @Override
  public UploadInfo append(UploadInfo upload, InputStream inputStream)
      throws IOException, TusException {
    UploadInfo info = storageServiceDelegate.append(upload, inputStream);
    uploadInfoCache.set(new WeakReference<>(info));
    return info;
  }

  @Override
  public void setMaxUploadSize(Long maxUploadSize) {
    storageServiceDelegate.setMaxUploadSize(maxUploadSize);
  }

  @Override
  public long getMaxUploadSize() {
    return storageServiceDelegate.getMaxUploadSize();
  }

  @Override
  public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
    UploadInfo uploadInfo = storageServiceDelegate.create(info, ownerKey);
    uploadInfoCache.set(new WeakReference<>(uploadInfo));
    return uploadInfo;
  }

  @Override
  public InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, UploadNotFoundException {
    return storageServiceDelegate.getUploadedBytes(uploadUri, ownerKey);
  }

  @Override
  public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
    return storageServiceDelegate.getUploadedBytes(id);
  }

  @Override
  public void copyUploadTo(UploadInfo info, OutputStream outputStream)
      throws UploadNotFoundException, IOException {
    storageServiceDelegate.copyUploadTo(info, outputStream);
    uploadInfoCache.set(new WeakReference<>(info));
  }

  @Override
  public void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException {
    storageServiceDelegate.cleanupExpiredUploads(uploadLockingService);
    // Since any cached uploads was potentially removed by the storage service
    // we clean the cache to prevent any stale state
    cleanupCache();
  }

  @Override
  public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException {
    storageServiceDelegate.removeLastNumberOfBytes(uploadInfo, byteCount);
    uploadInfoCache.set(new WeakReference<>(uploadInfo));
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    storageServiceDelegate.terminateUpload(uploadInfo);
    // Since the upload is terminated and potentially removed by the storage service
    // we clean the cache to prevent any stale state
    cleanupCache();
  }

  @Override
  public Long getUploadExpirationPeriod() {
    return storageServiceDelegate.getUploadExpirationPeriod();
  }

  @Override
  public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {
    storageServiceDelegate.setUploadExpirationPeriod(uploadExpirationPeriod);
  }

  @Override
  public void setUploadConcatenationService(UploadConcatenationService concatenationService) {
    storageServiceDelegate.setUploadConcatenationService(concatenationService);
  }

  @Override
  public UploadConcatenationService getUploadConcatenationService() {
    return storageServiceDelegate.getUploadConcatenationService();
  }

  @Override
  public UploadLock lockUploadByUri(String requestUri) throws TusException, IOException {
    UploadLock uploadLock = lockingServiceDelegate.lockUploadByUri(requestUri);
    return new CachedLock(uploadLock);
  }

  @Override
  public void cleanupStaleLocks() throws IOException {
    lockingServiceDelegate.cleanupStaleLocks();
    cleanupCache();
  }

  @Override
  public boolean isLocked(UploadId id) {
    return lockingServiceDelegate.isLocked(id);
  }

  private void cleanupCache() {
    WeakReference<UploadInfo> ref = uploadInfoCache.get();
    if (ref != null) {
      uploadInfoCache.remove();
      ref.clear();
    }
  }

  class CachedLock implements UploadLock {

    private final UploadLock delegate;

    CachedLock(UploadLock delegate) {
      this.delegate = delegate;
    }

    @Override
    public String getUploadUri() {
      return delegate != null ? delegate.getUploadUri() : null;
    }

    @Override
    public void release() {
      if (delegate != null) {
        delegate.release();
      }
    }

    @Override
    public void close() throws IOException {
      if (delegate != null) {
        delegate.close();
      }
      cleanupCache();
    }
  }
}
