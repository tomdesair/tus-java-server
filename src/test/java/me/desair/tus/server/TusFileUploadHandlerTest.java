package me.desair.tus.server;

import org.junit.Ignore;
import org.junit.Test;

public class TusFileUploadHandlerTest {

    private TusFileUploadHandler tusFileUploadHandler = new TusFileUploadHandler(null, null);

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