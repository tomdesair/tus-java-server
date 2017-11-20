package me.desair.tus.server;

import org.junit.Before;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.junit.Ignore;
import org.junit.Test;

public class TusFileUploadReceivingServiceTest {

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
    public void testProcess() throws Exception {
        //TODO
    }

}