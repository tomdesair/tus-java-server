package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
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
public class RufhProtocolHeadTest {

  private ResumableUploadsForHttpProtocol protocol;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    protocol = new ResumableUploadsForHttpProtocol();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();

    when(storageService.getUploadUri()).thenReturn("/files");
  }

  /**
   * Section 4.3.2 (Server Behavior - Offset Retrieval) of draft-11: "A successful response to a
   * HEAD request against an upload resource MUST include the offset in the Upload-Offset header
   * field, MUST include the completeness state in the Upload-Complete header field, MUST include
   * the length in the Upload-Length header field (unless omitted), MUST indicate limits in the
   * Upload-Limit header field, and SHOULD include the Cache-Control header field with value
   * no-store."
   */
  @Test
  public void testHeadOffsetRetrievalIncompleteUpload() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/test-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(2500L);
    info.setLength(10000L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.getMaxUploadSize()).thenReturn(500000L);

    protocol.validate(HttpMethod.HEAD, request, storageService, null, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.HEAD,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        null,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("2500"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LENGTH), is("10000"));
    assertThat(response.getHeader(HttpHeader.CACHE_CONTROL), is("no-store"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=500000"));
  }

  /**
   * Section 6.2 (Status Response for Completed Upload): "HEAD /upload/a9edb781b HTTP/1.1 HTTP/1.1
   * 204 No Content Upload-Complete: ?1 Upload-Offset: 100000000 Upload-Length: 100000000
   * Cache-Control: no-store"
   */
  @Test
  public void testHeadOffsetRetrievalCompletedUpload() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/test-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(10000L);
    info.setLength(10000L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(HttpMethod.HEAD, request, storageService, null, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.HEAD,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        null,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("10000"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  /**
   * Section 6.1 (Status Request): "The request MUST NOT contain Upload-Offset or Upload-Complete
   * header fields."
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testHeadWithForbiddenUploadOffsetHeader() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "100");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(HttpMethod.HEAD, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 6.1 (Status Request): "The request MUST NOT contain Upload-Offset or Upload-Complete
   * header fields."
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testHeadWithForbiddenUploadCompleteHeader() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(HttpMethod.HEAD, request, storageService, null, null, ProtocolVersion.RUFH);
  }
}
