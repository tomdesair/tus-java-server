package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
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
   * Section 3 (Feature Discovery): "If the server supports resumable uploads, it MUST respond to an
   * OPTIONS request with Accept-Patch containing application/partial-upload, and Upload-Draft
   * containing the draft version."
   */
  @Test
  public void testProcessOptionsRequest() throws Exception {
    when(storageService.getMaxUploadSize()).thenReturn(100000L);
    when(storageService.getMaxAppendSize()).thenReturn(50000L);

    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(
        response.getHeader(HttpHeader.ACCEPT_PATCH),
        is("application/partial-upload, application/offset+octet-stream"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
    assertThat(
        response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=100000, max-append-size=50000"));
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

  @Test
  public void testProcessWithNoLimits() throws Exception {
    when(storageService.getMaxUploadSize()).thenReturn(0L);
    when(storageService.getMaxAppendSize()).thenReturn(null);

    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessWithNullMaxAppendSize() throws Exception {
    when(storageService.getMaxUploadSize()).thenReturn(10000L);
    when(storageService.getMaxAppendSize()).thenReturn(null);

    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=10000"));
  }

  @Test
  public void testProcessWithZeroMaxAppendSize() throws Exception {
    when(storageService.getMaxUploadSize()).thenReturn(10000L);
    when(storageService.getMaxAppendSize()).thenReturn(0L);

    handler.process(
        HttpMethod.OPTIONS,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=10000"));
  }
}
