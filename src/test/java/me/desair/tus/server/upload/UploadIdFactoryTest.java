package me.desair.tus.server.upload;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class UploadIdFactoryTest {

    private UploadIdFactory idFactory;

    @Before
    public void setUp() {
        idFactory = new UploadIdFactory();
    }

    @Test(expected = NullPointerException.class)
    public void setUploadURINull() throws Exception {
        idFactory.setUploadURI(null);
    }

    @Test
    public void setUploadURINoTrailingSlash() throws Exception {
        idFactory.setUploadURI("/test/upload");
        assertThat(idFactory.getUploadURI(), is("/test/upload"));
    }

    @Test
    public void setUploadURIWithTrailingSlash() throws Exception {
        idFactory.setUploadURI("/test/upload/");
        assertThat(idFactory.getUploadURI(), is("/test/upload/"));
    }

    @Test
    public void readUploadId() throws Exception {
        idFactory.setUploadURI("/test/upload");

        assertThat(idFactory.readUploadId("/test/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
                hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
    }

    @Test
    public void readUploadIdTrailingSlash() throws Exception {
        idFactory.setUploadURI("/test/upload/");

        assertThat(idFactory.readUploadId("/test/upload/1911e8a4-6939-490c-b58b-a5d70f8d91fb"),
                hasToString("1911e8a4-6939-490c-b58b-a5d70f8d91fb"));
    }

    @Test
    public void readUploadIdNoUUID() throws Exception {
        idFactory.setUploadURI("/test/upload");

        assertThat(idFactory.readUploadId("/test/upload/not-a-uuid-value"), is(nullValue()));
    }

    @Test
    public void createId() throws Exception {
        assertThat(idFactory.createId(), not(nullValue()));
    }

}