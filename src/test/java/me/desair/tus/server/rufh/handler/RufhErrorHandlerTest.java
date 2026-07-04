package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
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
public class RufhErrorHandlerTest {

  private RufhErrorHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    handler = new RufhErrorHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertTrue(handler.supports(HttpMethod.POST));
  }

  @Test
  public void testIsErrorHandler() {
    assertTrue(handler.isErrorHandler());
  }

  /**
   * Section 8 (Offset Mismatch): "If the Upload-Offset request header field value does not match
   * the current offset... the server MUST respond with a 409 Conflict status code and an
   * application/problem+json response body including the expected-offset member."
   */
  @Test
  public void testProcessErrorHandler409Mismatch() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("mismatch-id"));
    info.setOffset(1000L); // Server offset is 1000
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(info);

    response.setStatus(409); // Mismatch set by validation exception handling flow

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner");

    assertThat(response.getStatus(), is(409));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(response.getContentAsString(), containsString("\"expected-offset\":1000"));
  }
}
