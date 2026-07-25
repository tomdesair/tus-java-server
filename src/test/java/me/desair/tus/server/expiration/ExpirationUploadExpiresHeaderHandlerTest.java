package me.desair.tus.server.expiration;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
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
public class ExpirationUploadExpiresHeaderHandlerTest {

  private ExpirationUploadExpiresHeaderHandler handler;
  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    handler = new ExpirationUploadExpiresHeaderHandler();
  }

  @Test
  public void supports() {
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PATCH), is(true));

    assertThat(handler.supports(HttpMethod.POST, ProtocolVersion.TUS_1_0_0), is(true));
    assertThat(handler.supports(HttpMethod.PATCH, ProtocolVersion.TUS_1_0_0), is(true));
    assertThat(handler.supports(HttpMethod.POST, ProtocolVersion.RUFH), is(false));
    assertThat(handler.supports(HttpMethod.PATCH, ProtocolVersion.RUFH), is(false));
    assertThat(handler.supports(HttpMethod.GET, ProtocolVersion.TUS_1_0_0), is(false));
  }

  @Test
  public void testProcessWithExpirationTimestamp() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setExpirationTimestamp(1516617791000L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);
    tusResponse.setHeader(HttpHeader.LOCATION, "/tus/upload/12345");

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is("1516617791000"));
  }

  @Test
  public void testProcessWithoutExpirationTimestamp() throws Exception {
    UploadInfo info = new UploadInfo();
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  @Test
  public void testProcessNullUploadInfo() throws Exception {
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    TusServletResponse tusResponse = new TusServletResponse(servletResponse);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(servletRequest),
        tusResponse,
        uploadStorageService,
        null);

    assertThat(tusResponse.getHeader(HttpHeader.UPLOAD_EXPIRES), is(nullValue()));
  }

  @Test
  public void isErrorHandler() {
    assertThat(handler.isErrorHandler(), is(true));
  }
}
