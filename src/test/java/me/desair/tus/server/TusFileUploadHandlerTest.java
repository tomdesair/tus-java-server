package me.desair.tus.server;

import org.junit.Before;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.junit.Ignore;
import org.junit.Test;

public class TusFileUploadHandlerTest {

    private MockHttpServletRequest servletRequest;
    private MockHttpServletResponse servletResponse;
    private TusFileUploadHandler tusFileUploadHandler;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        tusFileUploadHandler = new TusFileUploadHandler(servletRequest, servletResponse);
    }

    @Test(expected = NullPointerException.class)
    public void testWithFileStoreServiceNull() throws Exception {
        tusFileUploadHandler.withFileStoreService(null);
    }

    @Test
    @Ignore
    public void testProcess() throws Exception {
        //TODO
    }

}