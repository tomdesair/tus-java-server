package me.desair.tus.server;

import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.cache.ThreadLocalCachedStorageAndLockingService;
import me.desair.tus.server.upload.disk.DiskLockingService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import org.junit.Before;
import org.junit.Test;

public class ITTusFileUploadServiceCached extends ITTusFileUploadService {

    @Override
    @Before
    public void setUp() {
        super.setUp();
        tusFileUploadService = tusFileUploadService.withThreadLocalCache(true);
    }

    @Test
    public void testProcessUploadDoubleCached() throws Exception {
        UploadIdFactory idFactory = new UploadIdFactory();
        String path = storagePath.toAbsolutePath().toString();
        UploadStorageService uploadStorageService = new DiskStorageService(idFactory, path);
        UploadLockingService uploadLockingService = new DiskLockingService(idFactory, path);

        ThreadLocalCachedStorageAndLockingService service2 =
                new ThreadLocalCachedStorageAndLockingService(
                        uploadStorageService,
                        uploadLockingService);

        tusFileUploadService.withUploadStorageService(service2);
        tusFileUploadService.withUploadLockingService(service2);

        testProcessUploadTwoParts();
    }
}
