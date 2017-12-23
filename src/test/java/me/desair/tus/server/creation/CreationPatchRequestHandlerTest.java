package me.desair.tus.server.creation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UploadNotFoundException;
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
import java.io.InputStream;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
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
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processWithLengthAndNoHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(info);

        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processWithoutLengthAndHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(null);
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);

        verify(uploadStorageService, times(1)).update(info);
        assertThat(info.getLength(), is(10L));
    }

    @Test
    public void processWithoutLengthAndNoHeader() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setOffset(2L);
        info.setLength(null);
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(info);

        //servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        handler.process(HttpMethod.HEAD, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);

        verify(uploadStorageService, never()).update(info);
    }

    @Test
    public void processNotFound() throws Exception {
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(null);

        handler.process(HttpMethod.PATCH, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);
    }

    @Test
    public void processAppendNotFound() throws Exception {
        UploadInfo info = new UploadInfo();
        info.setId(UUID.randomUUID());
        info.setOffset(10L);
        when(uploadStorageService.getUploadInfo(anyString())).thenReturn(info);

        servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 10L);

        doThrow(new UploadNotFoundException("test")).when(uploadStorageService).update(any(UploadInfo.class));

        handler.process(HttpMethod.PATCH, servletRequest, new TusServletResponse(servletResponse), uploadStorageService);

        assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_INTERNAL_SERVER_ERROR));
    }
}