package me.desair.tus.server;

import org.junit.Before;

public class ITTusFileUploadServiceCached extends ITTusFileUploadService {
    @Override
    @Before
    public void setUp() {
        super.setUp();
        tusFileUploadService = tusFileUploadService.withThreadLocalCache(true);
    }
}
