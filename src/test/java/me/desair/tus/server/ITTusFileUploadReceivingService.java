package me.desair.tus.server;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ITTusFileUploadReceivingService {

    private MockHttpServletRequest servletRequest;
    private MockHttpServletResponse servletResponse;
    private TusFileUploadReceivingService tusFileUploadReceivingService;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        tusFileUploadReceivingService = new TusFileUploadReceivingService();
    }

    @Test(expected = NullPointerException.class)
    public void testWithFileStoreServiceNull() throws Exception {
        tusFileUploadReceivingService.withUploadStorageService(null);
    }

    @Test
    @Ignore
    public void testProcessCompleteUpload() throws Exception {
        //TODO

        //Create upload

        //Upload bytes

        //Check with HEAD request upload is complete

        //Get upload info from service

        //Get uploaded bytes from service

    }

    @Test
    @Ignore
    public void testProcessUploadTwoParts() throws Exception {
        //TODO

        //Create upload

        //Upload part 1 bytes

        //Check with HEAD request upload is still in progress

        //Upload part 2 bytes

        //Check with HEAD request upload is complete

        //Get upload info from service

        //Get uploaded bytes from service
    }

    @Test
    @Ignore
    public void testOptions() throws Exception {
        //TODO
        //Do options request and check response headers
    }

    @Test
    @Ignore
    public void testHeadOnNonExistingUpload() throws Exception {
        //TODO
    }

    @Test
    @Ignore
    public void testInvalidTusResumable() throws Exception {
        //TODO
    }

    @Test
    @Ignore
    public void testMaxUploadLengthExceeded() throws Exception {
        //TODO
    }

}