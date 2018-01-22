package me.desair.tus.server.expiration;


import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.ParseException;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.class)
public class ExpirationPatchRequestHandlerTest {

    private ExpirationPatchRequestHandler handler;

    private MockHttpServletRequest servletRequest;

    private MockHttpServletResponse servletResponse;

    @Mock
    private UploadStorageService uploadStorageService;

    @Before
    public void setUp() {
        servletRequest = new MockHttpServletRequest();
        servletResponse = new MockHttpServletResponse();
        handler = new ExpirationPatchRequestHandler();
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
    public void testInProgressUpload() throws Exception {
        UploadInfo info = createUploadInfo();
        info.setOffset(2L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(info);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, times(1)).update(info);
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is("1516614191000"));
    }

    @Test
    public void testNoUpload() throws Exception {
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(null);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, never()).update(any(UploadInfo.class));
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
    }

    @Test
    public void testFinishedUpload() throws Exception {
        UploadInfo info = createUploadInfo();
        info.setOffset(10L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(info);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, never()).update(any(UploadInfo.class));
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
    }

    @Test
    public void testNullExpiration() throws Exception {
        UploadInfo info = createUploadInfo();
        info.setOffset(8L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(info);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(null);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, never()).update(any(UploadInfo.class));
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
    }

    @Test
    public void testZeroExpiration() throws Exception {
        UploadInfo info = createUploadInfo();
        info.setOffset(8L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(info);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(0L);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, never()).update(any(UploadInfo.class));
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
    }

    @Test
    public void testNegativeExpiration() throws Exception {
        UploadInfo info = createUploadInfo();
        info.setOffset(8L);
        info.setLength(10L);
        when(uploadStorageService.getUploadInfo(anyString(), anyString())).thenReturn(info);
        when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(-10L);

        TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
        handler.process(HttpMethod.PATCH, new TusServletRequest(servletRequest), tusResponse, uploadStorageService, null);

        verify(uploadStorageService, never()).update(any(UploadInfo.class));
        assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
    }

    private UploadInfo createUploadInfo() {
        return new UploadInfo() {
            @Override
            protected long getCurrentTime() {
                try {
                    return DateFormatUtils.ISO_8601_EXTENDED_DATETIME_FORMAT.parse("2018-01-20T10:43:11").getTime();
                } catch (ParseException e) {
                    return 0L;
                }
            }
        };
    }

}