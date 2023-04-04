package me.desair.tus.server.creation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import jakarta.servlet.http.HttpServletResponse;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.Silent.class)
public class CreationPatchRequestHandlerTest {

    private CreationPatchRequestHandler handler;

    private MockHttpServletRequest servletRequest;

    private MockHttpServletResponse servletResponse;

    @Mock
    private UploadStorageService uploadStorageService;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        handler = new CreationPatchRequestHandler();
    }

    @Test
    public void supports() throws Exception {
        assertThat(handler.supports(HttpMethod.GET), is(false));
        assertThat(handler.supports(HttpMethod.POST), is(false));
        assertThat(handler.supports(HttpMethod.PUT), is(false));
        assertThat(handler.supports(HttpMethod.DELETE), is(false));
        assertThat(handler.supports(HttpMethod.HEAD), is(false));
        assertThat(handler.supports(HttpMethod.OPTIONS), is(false));
        assertThat(handler.supports(HttpMethod.PATCH), is(true));
        assertThat(handler.supports(null), is(false));
    }


    @Test
    public void processWithLengthAndHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processWithLengthAndNoHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(info);

        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processWithoutLengthAndHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(null);
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, times(1)).update(info);
        assertThat(info.getLength(), is(10L));
    }

    @Test
    public void processWithoutLengthAndNoHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(null);
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(info);

        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processNotFound() throws Exception {
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(null);

        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);
    }

    @Test
    public void processAppendNotFound() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setId(new UploadId(UUID.randomUUID()));
        info.setOffset(10L);
        when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class))).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        doThrow(new UploadNotFoundException("test")).when(uploadStorageService).update(any(UploadInfo.class));

        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest),
                new TusServletResponse(servletResponse), uploadStorageService, null);

        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
    }
}