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
}
