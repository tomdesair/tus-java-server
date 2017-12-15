package me.desair.tus.server.core;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.core.validation.ContentTypeValidator;
import me.desair.tus.server.exception.*;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ITCoreProtocol {

    private CoreProtocol tusFeature;

    private MockHttpServletRequest servletRequest;

    private MockHttpServletResponse servletResponse;

    @Mock
    private UploadStorageService uploadStorageService;

    private UploadInfo uploadInfo;

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

        executeCall(HttpMethod.forName("TEST"));
    }

    @Test
    public void testHeadWithLength() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.HEAD);

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

        executeCall(HttpMethod.HEAD);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "2");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, null);
        assertResponseHeader(HttpHeader.CACHE_CONTROL, "no-store");
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test(expected = UploadNotFoundException.class)
    public void testHeadNotFound() throws Exception {
        //We don't prepare an upload info
        setRequestHeaders(HttpHeader.TUS_RESUMABLE);

        executeCall(HttpMethod.HEAD);
    }

    @Test(expected = InvalidTusResumableException.class)
    public void testHeadInvalidVersion() throws Exception {
        setRequestHeaders();
        prepareUploadInfo(2L, null);
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");

        executeCall(HttpMethod.HEAD);
    }

    @Test
    public void testPatchSuccess() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.UPLOAD_OFFSET, HttpHeader.CONTENT_LENGTH);

        executeCall(HttpMethod.PATCH);

        verify(uploadStorageService, times(1)).append(any(UploadInfo.class), any(InputStream.class));

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "2");
        assertResponseHeader(HttpHeader.UPLOAD_LENGTH, null);
        assertResponseHeader(HttpHeader.CACHE_CONTROL, null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test(expected = InvalidContentTypeException.class)
    public void testPatchInvalidContentType() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.UPLOAD_OFFSET, HttpHeader.CONTENT_LENGTH);

        executeCall(HttpMethod.PATCH);
    }

    @Test(expected = UploadOffsetMismatchException.class)
    public void testPatchInvalidUploadOffset() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_LENGTH);
        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 5);

        executeCall(HttpMethod.PATCH);
    }

    @Test(expected = InvalidContentLengthException.class)
    public void testPatchInvalidContentLength() throws Exception {
        prepareUploadInfo(2L, 10L);
        setRequestHeaders(HttpHeader.TUS_RESUMABLE, HttpHeader.CONTENT_TYPE, HttpHeader.UPLOAD_OFFSET);
        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 9);

        executeCall(HttpMethod.PATCH);
    }

    @Test
    public void testOptionsWithMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(107374182400L);

        setRequestHeaders();

        executeCall(HttpMethod.OPTIONS);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, "107374182400");
        assertResponseHeader(HttpHeader.TUS_EXTENSION, null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    @Test
    public void testOptionsWithNoMaxSize() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(0L);

        setRequestHeaders();

        executeCall(HttpMethod.OPTIONS);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, null);
        assertResponseHeader(HttpHeader.TUS_EXTENSION, null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }


    @Test
    public void testOptionsIgnoreTusResumable() throws Exception {
        when(uploadStorageService.getMaxUploadSize()).thenReturn(10L);

        setRequestHeaders();
        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");

        executeCall(HttpMethod.OPTIONS);

        assertResponseHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_VERSION, "1.0.0");
        assertResponseHeader(HttpHeader.TUS_MAX_SIZE, "10");
        assertResponseHeader(HttpHeader.TUS_EXTENSION, null);
        assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private void prepareUploadInfo(final Long offset, final Long length) throws IOException {
        uploadInfo = new UploadInfo();
        uploadInfo.setOffset(offset);
        uploadInfo.setLength(length);
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(uploadInfo);
        when(uploadStorageService.append(any(UploadInfo.class), any(InputStream.class))).thenReturn(uploadInfo);
    }

    private void setRequestHeaders(String... headers) {
        if(headers != null && headers.length > 0) {
            for (String header : headers) {
                switch (header) {
                    case HttpHeader.TUS_RESUMABLE:
                        servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
                        break;
                    case HttpHeader.CONTENT_TYPE:
                        servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
                        break;
                    case HttpHeader.UPLOAD_OFFSET:
                        servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, uploadInfo.getOffset());
                        break;
                    case HttpHeader.CONTENT_LENGTH:
                        servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadInfo.getLength() - uploadInfo.getOffset());
                        break;
                }
            }
        }
    }

    private void executeCall(final HttpMethod method) throws TusException, IOException {
        tusFeature.validate(method, servletRequest, uploadStorageService);
        tusFeature.process(method, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);
    }

    private void assertResponseHeader(final String header, final String value) {
        assertThat(servletResponse.getHeader(header), is(value));
    }

    private void assertResponseStatus(final int httpStatus) {
        assertThat(servletResponse.getStatus(), is(httpStatus));
    }
}