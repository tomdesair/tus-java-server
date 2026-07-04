package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Tests for {@link RufhResponseHeadersHandler}. */
public class RufhResponseHeadersHandlerTest {

  private RufhResponseHeadersHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Before
  public void setUp() {
    handler = new RufhResponseHeadersHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupportsAllMethods() {
    assertTrue(handler.supports(HttpMethod.POST));
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertTrue(handler.supports(HttpMethod.OPTIONS));
    assertTrue(handler.supports(HttpMethod.HEAD));
    assertTrue(handler.supports(HttpMethod.DELETE));
  }

  @Test
  public void testIsErrorHandler() {
    assertTrue(handler.isErrorHandler());
  }

  @Test
  public void testProcessSetsDraftHeader() throws Exception {
    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        null,
        null,
        null,
        null);

    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
  }
}
