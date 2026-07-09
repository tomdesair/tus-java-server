package me.desair.tus.server.cors;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class CorsRequestHandlerTest {

  private CorsRequestHandler handler;
  private MockHttpServletRequest mockReq;
  private MockHttpServletResponse mockResp;

  @Before
  public void setUp() {
    handler = new CorsRequestHandler();
    mockReq = new MockHttpServletRequest();
    mockResp = new MockHttpServletResponse();
  }

  @Test
  public void supports() {
    assertThat(handler.supports(HttpMethod.GET), is(true));
    assertThat(handler.supports(HttpMethod.OPTIONS), is(true));
  }

  @Test
  public void isErrorHandler() {
    assertThat(handler.isErrorHandler(), is(true));
  }

  @Test
  public void processNoOrigin() throws Exception {
    TusServletRequest req = new TusServletRequest(mockReq);
    TusServletResponse resp = new TusServletResponse(mockResp);

    handler.process(HttpMethod.GET, req, resp, null, null);

    assertThat(mockResp.getHeader("Access-Control-Allow-Origin"), is((Object) null));
  }

  @Test
  public void processWithOrigin() throws Exception {
    mockReq.addHeader("Origin", "https://example.com");
    TusServletRequest req = new TusServletRequest(mockReq);
    TusServletResponse resp = new TusServletResponse(mockResp);

    handler.process(HttpMethod.GET, req, resp, null, null);

    assertThat(mockResp.getHeader("Access-Control-Allow-Origin"), is("https://example.com"));
    assertThat(
        mockResp.getHeader("Access-Control-Expose-Headers"),
        is(
            "Upload-Offset, Upload-Length, Upload-Metadata, Upload-Expires, Upload-Concat, "
                + "Tus-Resumable, Tus-Version, Tus-Max-Size, Tus-Extension, Tus-Checksum-Algorithm, Location"));
  }

  @Test
  public void processPreflight() throws Exception {
    mockReq.addHeader("Origin", "https://example.com");
    mockReq.addHeader("Access-Control-Request-Method", "PATCH");
    TusServletRequest req = new TusServletRequest(mockReq);
    TusServletResponse resp = new TusServletResponse(mockResp);

    handler.process(HttpMethod.OPTIONS, req, resp, null, null);

    assertThat(mockResp.getHeader("Access-Control-Allow-Origin"), is("https://example.com"));
    assertThat(
        mockResp.getHeader("Access-Control-Allow-Methods"),
        is("POST, GET, HEAD, PATCH, DELETE, OPTIONS"));
    assertThat(
        mockResp.getHeader("Access-Control-Allow-Headers"),
        is(
            "Origin, X-Requested-With, Content-Type, Upload-Length, Upload-Offset, Upload-Metadata, "
                + "Upload-Expires, Upload-Checksum, Upload-Concat, Upload-Defer-Length, Tus-Resumable, X-HTTP-Method-Override"));
    assertThat(mockResp.getHeader("Access-Control-Max-Age"), is("86400"));
  }
}
