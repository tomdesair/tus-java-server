package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import me.desair.tus.server.HttpMethod;
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
public class RufhOptionsRequestHandlerTest {

  private RufhOptionsRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    handler = new RufhOptionsRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.OPTIONS));
    assertFalse(handler.supports(HttpMethod.POST));
  }

  /**
   * Section 4.1.4 (Limits): In draft-12, Accept-Patch is removed from OPTIONS response in RUFH
   * mode, while Upload-Limit header is included when limits apply.
   */
  @Test
  public void testProcessOptionsRequest() throws Exception {
    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader("Accept-Patch"), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessWithNullUploadStorageService() throws Exception {
    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        null,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
  }
}
