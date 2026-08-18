package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.InterruptibleInputStream;
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
public class RufhProtocolCreationTest {

  private ResumableUploadsForHttpProtocol protocol;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;
  @Mock private UploadLockingService lockingService;

  @Before
  public void setUp() {
    protocol = new ResumableUploadsForHttpProtocol();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();

    when(storageService.getUploadUri()).thenReturn("/files");
  }

  /**
   * Section 4.2.1 (Upload Creation - Client Behavior): "A client can start a resumable upload from
   * any request that can carry content by including the Upload-Complete header field. If the client
   * knows the representation data's length, it SHOULD indicate the length in the request through
   * the Upload-Length header field."
   *
   * <p>Section 4.2.2 (Upload Creation - Server Behavior): "If the Upload-Complete header field is
   * set to false, the client intends to transfer the representation over multiple requests. If the
   * request content was fully received, the server MUST include the Location response header field
   * pointing to the upload resource... Servers are RECOMMENDED to use the 201 (Created) status
   * code."
   */
  @Test
  public void testUploadCreationPartialWithLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "10000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setLength(10000L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/" + info.getId()));
  }

  /**
   * Section 4.2.1 & 4.2.2 (Complete Upload Creation): "If the Upload-Complete request header field
   * is set to true, the client intends to transfer the entire representation data in one request.
   * If the request content was fully received, no resumable upload is needed and the resource
   * proceeds to process the request and generate a response."
   */
  @Test
  public void testUploadCreationCompleteSingleRequest() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "11");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("Hello World".getBytes());

    UploadInfo info = new UploadInfo();
    info.setLength(11L);
    info.setOffset(11L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("12"));
  }

  /**
   * Section 4.1.3 (Length) & Section 7.3 (Inconsistent Length): "If indicators (1)
   * [Upload-Complete: ?1 with Content-Length] and (2) [Upload-Length] are both present in the same
   * request, their indicated lengths MUST match. If multiple requests include indicators, their
   * indicated values MUST match. A server can use the problem type of
   * 'https://iana.org/assignments/http-problem-types#inconsistent-upload-length' in responses to
   * indicate inconsistent length values."
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testInconsistentUploadLengthValidation() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("Hello World".getBytes()); // 11 bytes != 1000 bytes

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 7.2 (Inconsistent Length Response): "The server responds with a 400 (Bad Request)
   * status code and the Upload-Complete: ?0 header field."
   */
  @Test
  public void testInconsistentUploadLengthIncludesUploadCompleteFalse() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("Hello World".getBytes());

    me.desair.tus.server.rufh.handler.RufhErrorHandler errorHandler =
        new me.desair.tus.server.rufh.handler.RufhErrorHandler();
    me.desair.tus.server.exception.InconsistentUploadLengthException ex =
        new me.desair.tus.server.exception.InconsistentUploadLengthException("Length mismatch");

    TusServletResponse servletResponse = new TusServletResponse(response);
    errorHandler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        servletResponse,
        storageService,
        lockingService,
        null,
        ex);

    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  /**
   * Section 4.2.1 & 4.2.2 (Upload Creation without Upload-Length): "If the Upload-Complete header
   * field is set to true, but Upload-Length is omitted, the server determines the length from the
   * content sent."
   */
  @Test
  public void testUploadCreationCompleteWithoutUploadLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("Hello World".getBytes());

    UploadInfo info = new UploadInfo();
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    UploadInfo appended = new UploadInfo();
    appended.setOffset(11L);
    appended.setId(info.getId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(appended);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("11"));
  }

  /**
   * Section 4.1.4 (Limits): "The server might not create an upload resource if the length deduced
   * from the upload creation request is larger than the maximum size."
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testUploadCreationExceedingMaxSize() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "200000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    when(storageService.getMaxUploadSize()).thenReturn(100000L);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 4.2.2 (Upload Creation - Streaming & Lock Registration): Tests that when creating an
   * upload with body content, the input stream is wrapped in an InterruptibleInputStream and
   * registered with the UploadLockingService to support lock contention resolution.
   */
  @Test
  public void testUploadCreationRegistersInterruptibleStreamWithLockingService() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("Stream data".getBytes());

    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    verify(lockingService)
        .registerInputStream(eq("/files/" + info.getId()), any(InterruptibleInputStream.class));
  }

  /**
   * Section 4.1.4 (min-append-size): "This limit does not apply to upload creation requests with no
   * content..."
   */
  @Test
  public void test0BytePostCreationBypassesMinAppendSize() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "10000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    // 0-byte payload content

    UploadInfo info = new UploadInfo();
    info.setLength(10000L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.getMinAppendSize()).thenReturn(1000L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 4.1.4 (min-append-size): "...or to requests completing the upload by including the
   * Upload-Complete: ?1 header field."
   */
  @Test
  public void testUploadCompleteBypassesMinAppendSize() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("Small".getBytes()); // 5 bytes < 1000L min-append-size

    UploadInfo info = new UploadInfo();
    info.setOffset(5L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.getMinAppendSize()).thenReturn(1000L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 4.1.4 (min-append-size): Non-exempt small creation POST payload (< minAppendSize)
   * without Upload-Complete: ?1 is rejected.
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testCreationSmallPayloadRejectedByMinAppendSize() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("Small payload".getBytes()); // 13 bytes < 1000L

    when(storageService.getMinAppendSize()).thenReturn(1000L);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 4.1.4 (min-size): Upload creation with Upload-Length smaller than minSize throws 400
   * Bad Request.
   */
  @Test(expected = me.desair.tus.server.exception.TusException.class)
  public void testUploadCreationSmallerThanMinSizeThrowsTusException() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "50");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    when(storageService.getMinSize()).thenReturn(100L);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  @Test
  public void testUploadCreationValidMinSizeAndMinAppendSize() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("This payload is larger than 10 bytes".getBytes());

    when(storageService.getMinSize()).thenReturn(100L);
    when(storageService.getMinAppendSize()).thenReturn(10L);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 4.2.1 (Upload Creation): "All request methods allowing content can be used to start a
   * resumable upload (e.g. POST, PUT, PATCH)."
   */
  @Test
  public void testUploadCreationPutMethod() throws Exception {
    request.setMethod("PUT");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    protocol.validate(
        HttpMethod.PUT, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.PUT,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(200));
  }

  /** Section 4.2.1 (Upload Creation): Creation using PATCH method on creation endpoint. */
  @Test
  public void testUploadCreationPatchMethod() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.PATCH,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(200));
  }

  @Test
  public void testUploadCreationWithPreCreatedUploadInfoAndLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "500");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo preCreated = new UploadInfo();
    preCreated.setId(new UuidUploadIdFactory().createId());
    request.setAttribute("me.desair.tus.preCreatedUploadInfo", preCreated);

    when(storageService.append(any(UploadInfo.class), any())).thenReturn(preCreated);

    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(201));
  }

  @Test
  public void testUploadCreationWithPreCreatedUploadInfoWithoutLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo preCreated = new UploadInfo();
    preCreated.setId(new UuidUploadIdFactory().createId());
    request.setAttribute("me.desair.tus.preCreatedUploadInfo", preCreated);

    when(storageService.append(any(UploadInfo.class), any())).thenReturn(preCreated);

    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(201));
  }

  /**
   * Section 4.2.2 (Upload Creation - Server Behavior): "If the server decides to create the upload
   * resource, it MUST acknowledge this by sending a response with a 2xx (Successful) or 104 (Upload
   * Resumption Supported) status code and MUST set the Location header field to the URI of the
   * upload resource... The URI of the upload resource MAY be relative to the request target (see
   * Section 4.2 of [RFC3986])."
   *
   * <p>Tests upload creation when configured with an absolute base URL.
   */
  @Test
  public void testUploadCreationWithAbsoluteBaseUrl() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "10000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/files");

    UploadInfo info = new UploadInfo();
    info.setLength(10000L);
    info.setOffset(0L);
    info.setId(new UuidUploadIdFactory().createId());

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    protocol.validate(
        HttpMethod.POST, request, storageService, lockingService, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.POST,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
    assertThat(
        response.getHeader(HttpHeader.LOCATION),
        is("https://upload.example.com/files/" + info.getId()));
  }
}
