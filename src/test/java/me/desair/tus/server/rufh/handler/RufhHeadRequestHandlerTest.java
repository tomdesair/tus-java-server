package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
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
public class RufhHeadRequestHandlerTest {

  private RufhHeadRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    handler = new RufhHeadRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.HEAD));
    assertFalse(handler.supports(HttpMethod.POST));
  }

  /**
   * Section 6.2 (Status Response): "A successful response to a HEAD request against an upload
   * resource MUST include the offset in the Upload-Offset header field, MUST include the
   * completeness state in the Upload-Complete header field, and SHOULD include the Cache-Control
   * header field with value no-store."
   */
  @Test
  public void testProcessIncompleteHeadRequest() throws Exception {
    request.setRequestURI("/files/incomplete-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("incomplete-id"));
    info.setOffset(2500L);
    info.setLength(10000L);
    when(storageService.getUploadInfo("/files/incomplete-id", "owner")).thenReturn(info);
    when(storageService.getMaxUploadSize()).thenReturn(500000L);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner");

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("2500"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LENGTH), is("10000"));
    assertThat(response.getHeader(HttpHeader.CACHE_CONTROL), is("no-store"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=500000"));
  }

  /**
   * Section 6.2 (Status Response for Completed Upload): "HEAD /upload/a9edb781b HTTP/1.1 HTTP/1.1
   * 204 No Content Upload-Complete: ?1 Upload-Offset: 100000000"
   */
  @Test
  public void testProcessCompletedHeadRequest() throws Exception {
    request.setRequestURI("/files/completed-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("completed-id"));
    info.setOffset(10000L);
    info.setLength(10000L); // Completed
    when(storageService.getUploadInfo("/files/completed-id", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner");

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("10000"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessWithMaxAppendSizeAndNoLength() throws Exception {
    request.setRequestURI("/files/incomplete-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("incomplete-id"));
    info.setOffset(1000L);
    // Length is null
    when(storageService.getUploadInfo("/files/incomplete-id", "owner")).thenReturn(info);
    when(storageService.getMaxUploadSize()).thenReturn(0L);
    when(storageService.getMaxAppendSize()).thenReturn(5000L);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner");

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1000"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LENGTH), org.hamcrest.CoreMatchers.nullValue());
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-append-size=5000"));
  }

  @Test
  public void testProcessWithNullMaxAppendSize() throws Exception {
    request.setRequestURI("/files/incomplete-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("incomplete-id"));
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/incomplete-id", "owner")).thenReturn(info);
    when(storageService.getMaxUploadSize()).thenReturn(10000L);
    when(storageService.getMaxAppendSize()).thenReturn(null);

    handler.process(
        HttpMethod.HEAD,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner");

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=10000"));
  }
}
