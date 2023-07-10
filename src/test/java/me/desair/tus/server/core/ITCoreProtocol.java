package me.desair.tus.server.core;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import jakarta.servlet.http.HttpServletResponse;

import me.desair.tus.server.AbstractTusExtensionIntegrationTest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.exception.InvalidContentTypeException;
import me.desair.tus.server.exception.InvalidTusResumableException;
import me.desair.tus.server.exception.UnsupportedMethodException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.exception.UploadOffsetMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ITCoreProtocol extends AbstractTusExtensionIntegrationTest {

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        tusFeature = new CoreProtocol();
        uploadInfo = null;
    }

    @Test
    public void getName() throws Exception {
        assertThat(tusFeature.getName(), is("core"));
    }

    @Test(expected = UnsupportedMethodException.class)
    public void testUnsupportedHttpMethod() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.forName("TEST"), false);
    }

    @Test
    public void testHeadWithLength() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.HEAD, false);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "2");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, "10");
        assertResponseHeader(HttpHeader.CACHE_CONTROL, "no-store");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    public void testHeadWithoutLength() throws Exception {
        prepareUploadInfo(2L, null);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.HEAD, false);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "2");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, (String) null);
        assertResponseHeader(HttpHeader.CACHE_CONTROL, "no-store");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test(expected = UploadNotFoundException.class)
    public void testHeadNotFound() throws Exception {
        //We don't prepare an upload info
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.HEAD, false);
    }

    @Test(expected = InvalidTusResumableException.class)
    public void testHeadInvalidVersion() throws Exception {
        setRequestHeaders();
        prepareUploadInfo(2L, null);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");

        executeCall(HttpMethod.HEAD, false);
    }

    @Test
    public void testPatchSuccess() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.UPLOAD_OFFSET,
                HttpHeader.CONTENT_LENGTH);

        executeCall(HttpMethod.PATCH, false);

        verify(uploadStorageService, times(1))
                .append(any(UploadInfo.class), any(InputStream.class));

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "2");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, (String) null);
        assertResponseHeader(HttpHeader.CACHE_CONTROL, (String) null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test(expected = InvalidContentTypeException.class)
    public void testPatchInvalidContentType() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.UPLOAD_OFFSET, HttpHeader.CONTENT_LENGTH);

        executeCall(HttpMethod.PATCH, false);
    }

    @Test(expected = UploadOffsetMismatchException.class)
    public void testPatchInvalidUploadOffset() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_LENGTH);
        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 5);

        executeCall(HttpMethod.PATCH, false);
    }

    @Test(expected = InvalidContentLengthException.class)
    public void testPatchInvalidContentLength() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.UPLOAD_OFFSET);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 9);

        executeCall(HttpMethod.PATCH, false);
    }

    @Test
    public void testOptionsWithMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(107374182400L);

        setRequestHeaders();

        executeCall(HttpMethod.OPTIONS, false);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, "107374182400");
        assertResponseHeader(HttpHeader.TUS_EXTENSION, (String) null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    public void testOptionsWithNoMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(0L);

        setRequestHeaders();

        executeCall(HttpMethod.OPTIONS, false);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, (String) null);
        assertResponseHeader(HttpHeader.TUS_EXTENSION, (String) null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }


    @Test
    public void testOptionsIgnoreTusResumable() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(10L);

        setRequestHeaders();
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");

        executeCall(HttpMethod.OPTIONS, false);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, "10");
        assertResponseHeader(HttpHeader.TUS_EXTENSION, (String) null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}