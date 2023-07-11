package me.desair.tus.server.expiration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.ParseException;
import java.util.Locale;
import java.util.TimeZone;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.time.FastDateFormat;
import org.apache.commons.lang3.time.TimeZones;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ExpirationRequestHandlerTest {

  private static final FastDateFormat DATE_FORMAT =
      FastDateFormat.getInstance(
          "yyyy-MM-dd HH:mm:ss", TimeZone.getTimeZone(TimeZones.GMT_ID), Locale.US);

  private ExpirationRequestHandler handler;

  private MockHttpServletRequest servletRequest;

  private MockHttpServletResponse servletResponse;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new ExpirationRequestHandler();
  }

  @Test
  public void supports() throws Exception {
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PUT), is(false));
    assertThat(handler.supports(HttpMethod.DELETE), is(false));
    assertThat(handler.supports(HttpMethod.HEAD), is(false));
    assertThat(handler.supports(HttpMethod.OPTIONS), is(false));
    assertThat(handler.supports(HttpMethod.PATCH), is(true));
    assertThat(handler.supports(null), is(false));
  }

  @Test
  public void testCreatedUpload() throws Exception {
    UploadInfo info = createUploadInfo();
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    tusResponse.setHeader(HttpHeader.LOCATION, "/tus/upload/12345");
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, times(1)).update(info);
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is("1516617791000"));
  }

  @Test
  public void testInProgressUpload() throws Exception {
    UploadInfo info = createUploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, times(1)).update(info);
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is("1516617791000"));
  }

  @Test
  public void testNoUpload() throws Exception {
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, never()).update(any(UploadInfo.class));
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  @Test
  public void testFinishedUpload() throws Exception {
    UploadInfo info = createUploadInfo();
    info.setOffset(10L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(172800000L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    // Upload Expires header must always be set
    verify(uploadStorageService, times(1)).update(info);
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is("1516617791000"));
  }

  @Test
  public void testNullExpiration() throws Exception {
    UploadInfo info = createUploadInfo();
    info.setOffset(8L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, never()).update(any(UploadInfo.class));
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  @Test
  public void testZeroExpiration() throws Exception {
    UploadInfo info = createUploadInfo();
    info.setOffset(8L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(0L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, never()).update(any(UploadInfo.class));
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  @Test
  public void testNegativeExpiration() throws Exception {
    UploadInfo info = createUploadInfo();
    info.setOffset(8L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(-10L);

    TusServletResponse tusResponse = new TusServletResponse(this.servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    verify(uploadStorageService, never()).update(any(UploadInfo.class));
    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  private UploadInfo createUploadInfo() {
    return new UploadInfo() {
      @Override
      protected long getCurrentTime() {
        try {
          return DATE_FORMAT.parse("2018-01-20 10:43:11").getTime();
        } catch (ParseException e) {
          return 0L;
        }
      }
    };
  }
}
