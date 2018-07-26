package me.desair.tus.server.upload.disk;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * Combined implementation of {@link UploadStorageService} and {@link UploadLockingService} based on
 * {@link DiskStorageService} but allowing to reduce disk operations during a request processing by caching UploadInfo
 * in the memory.
 * <p>
 * UploadLockingService service is used as a delegate to cleanup cached data on releasing a lock.
 */
public class CachedDiskAndLockingStorageService extends DiskStorageService implements UploadLockingService {
    private final ThreadLocal<WeakReference<UploadInfo>> uploadInfoCache = new ThreadLocal<>();
    private final UploadLockingService lockingServiceDelegate;

    public CachedDiskAndLockingStorageService(UploadIdFactory idFactory,
                                              String storagePath,
                                              UploadLockingService lockingServiceDelegate) {
        super(idFactory, storagePath);
        this.lockingServiceDelegate = lockingServiceDelegate;
    }

    @Override
    public UploadInfo getUploadInfo(UUID id) throws IOException {
        UploadInfo uploadInfo;
        WeakReference<UploadInfo> ref = uploadInfoCache.get();
        if (ref == null || (uploadInfo = ref.get()) == null || !id.equals(uploadInfo.getId())) {
            uploadInfo = super.getUploadInfo(id);
            uploadInfoCache.set(new WeakReference<>(uploadInfo));
        }
        return uploadInfo;
    }

    @Override
    public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
        super.update(uploadInfo);
        uploadInfoCache.set(new WeakReference<>(uploadInfo));
    }

    @Override
    public UploadLock lockUploadByUri(String requestURI) throws TusException, IOException {
        UploadLock uploadLock = lockingServiceDelegate.lockUploadByUri(requestURI);
        return new CachedLock(uploadLock);
    }

    @Override
    public void cleanupStaleLocks() throws IOException {
        lockingServiceDelegate.cleanupStaleLocks();
    }

    @Override
    public boolean isLocked(UUID id) {
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
            if (delegate != null) delegate.release();
        }

        @Override
        public void close() throws IOException {
            if (delegate != null) delegate.close();
            cleanupCache();
        }
    }
}
