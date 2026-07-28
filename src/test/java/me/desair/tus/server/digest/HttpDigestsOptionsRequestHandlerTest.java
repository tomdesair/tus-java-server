package me.desair.tus.server.digest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class HttpDigestsOptionsRequestHandlerTest {

  private HttpDigestsOptionsRequestHandler handler;
  private UploadStorageService uploadStorageService;
  private UploadLockingService uploadLockingService;

  @Before
  public void setUp() {
    handler = new HttpDigestsOptionsRequestHandler();
    uploadStorageService = mock(UploadStorageService.class);
    uploadLockingService = mock(UploadLockingService.class);
  }

  @Test
  public void testSupports() {
    assertThat(handler.supports(HttpMethod.OPTIONS), is(true));
    assertThat(handler.supports(HttpMethod.GET), is(false));
    assertThat(handler.supports(HttpMethod.POST), is(false));
  }

  @Test
  public void testProcess() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    TusServletRequest servletRequest = new TusServletRequest(request);
    TusServletResponse servletResponse =
        new TusServletResponse(new org.springframework.mock.web.MockHttpServletResponse());

    handler.process(
        HttpMethod.OPTIONS,
        servletRequest,
        servletResponse,
        uploadStorageService,
        uploadLockingService,
        "owner",
        null);

    String expectedValue = ChecksumAlgorithm.getSupportedHttpDigestAlgorithmsHeaderValue();
    assertThat(servletResponse.getHeader(HttpHeader.WANT_CONTENT_DIGEST), is(expectedValue));
    assertThat(servletResponse.getHeader(HttpHeader.WANT_REPR_DIGEST), is(expectedValue));
  }
}
