package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
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
public class RufhUploadLimitHeaderRequestHandlerTest {

  private RufhUploadLimitHeaderRequestHandler handler;
  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new RufhUploadLimitHeaderRequestHandler();
  }

  @Test
  public void supports() {
    assertThat(handler.supports(HttpMethod.OPTIONS), is(true));
    assertThat(handler.supports(HttpMethod.HEAD), is(true));
    assertThat(handler.supports(HttpMethod.GET), is(true));
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PATCH), is(true));
    assertThat(handler.supports(HttpMethod.DELETE), is(false));

    assertThat(handler.supports(HttpMethod.POST, ProtocolVersion.RUFH), is(true));
    assertThat(handler.supports(HttpMethod.POST, ProtocolVersion.TUS_1_0_0), is(false));
    assertThat(handler.supports(HttpMethod.DELETE, ProtocolVersion.RUFH), is(false));
  }

  @Test
  public void testProcessSetsUploadLimitHeader() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(10000L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=10000"));
  }

  @Test
  public void testProcessDefaultMinSizeHeader() throws Exception {
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_LIMIT), is("min-size=0"));
  }

  @Test
  public void testProcessCalculatesMaxAgeFromUploadInfo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setExpirationTimestamp(System.currentTimeMillis() + 60000L); // 60s in future
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    String uploadLimit = tusResponse.getHeader(HttpHeader.UPLOAD_LIMIT);
    assertThat(uploadLimit, is(notNullValue()));
    assertThat(uploadLimit.contains("max-age="), is(true));
  }

  @Test
  public void isErrorHandler() {
    assertThat(handler.isErrorHandler(), is(true));
  }

  @Test
  public void testNullParameters() throws Exception {
    // Calling process with null response or storageService should return without error or setting
    // headers
    handler.process(
        HttpMethod.POST, new TusServletRequest(servletRequest), null, uploadStorageService, null);
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        new TusServletResponse(servletResponse),
        null,
        null);
    assertThat(
        servletResponse.getHeader(HttpHeader.UPLOAD_LIMIT), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessWithMinAndAppendSizeLimits() throws Exception {
    when(uploadStorageService.getMinSize()).thenReturn(100L);
    when(uploadStorageService.getMaxAppendSize()).thenReturn(5000L);
    when(uploadStorageService.getMinAppendSize()).thenReturn(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    String uploadLimit = tusResponse.getHeader(HttpHeader.UPLOAD_LIMIT);
    assertThat(uploadLimit, is(notNullValue()));
    assertThat(uploadLimit.contains("min-size=100"), is(true));
    assertThat(uploadLimit.contains("max-append-size=5000"), is(true));
    assertThat(uploadLimit.contains("min-append-size=10"), is(true));
  }

  @Test
  public void testProcessMaxAgeFromStorageService() throws Exception {
    when(uploadStorageService.getUploadExpirationPeriod()).thenReturn(120000L); // 120s
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    String uploadLimit = tusResponse.getHeader(HttpHeader.UPLOAD_LIMIT);
    assertThat(uploadLimit, is(notNullValue()));
    assertThat(uploadLimit.contains("max-age=120"), is(true));
  }
}
