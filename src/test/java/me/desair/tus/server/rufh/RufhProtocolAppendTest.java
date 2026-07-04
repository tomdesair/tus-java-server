package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
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
public class RufhProtocolAppendTest {

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
   * Section 5.1 (Upload Append - Client Behavior): "A client can continue the upload and append
   * representation data by sending a PATCH request with the application/partial-upload media type
   * to the upload resource. The request MUST indicate the offset of the request content inside the
   * representation data by including the Upload-Offset header field."
   *
   * <p>Section 5.2 (Upload Append - Server Behavior): "If the Upload-Complete request header field
   * is set to false... the upload resource acknowledges the appended data by sending a 2xx response
   * with the Upload-Complete header field set to false."
   */
  @Test
  public void testUploadAppendPartialDataSuccess() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("chunk content".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);
    info.setLength(5000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(new UploadId("test-id"));
    updated.setOffset(1013L);
    updated.setLength(5000L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

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

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1013"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
  }

  /**
   * Section 4.4.2 (Server Behavior - Upload Append): "If the length is known, the server MUST
   * prevent the offset from exceeding the upload length by rejecting the request once the offset
   * exceeds the length, marking the upload resource invalid and rejecting any further interaction
   * with it."
   */
  @Test(expected = TusException.class)
  public void testAppendExceedingUploadLengthRejected() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "4990");
    request.setContent("This content is 30 bytes long".getBytes()); // 4990 + 30 = 5020 > 5000

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(4990L);
    info.setLength(5000L); // Declared length is 5000

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 7.2 (Completed Upload) of draft-11: "This section defines the
   * 'https://iana.org/assignments/http-problem-types#completed-upload' problem type. A server can
   * use this problem type when responding to an upload append request (Section 4.4) to indicate
   * that the upload has already been completed and cannot be modified."
   */
  @Test
  public void testCompletedUploadFormatsProblemJson() throws Exception {
    MockHttpServletResponse errResponse = new MockHttpServletResponse();
    errResponse.setStatus(400);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(5000L);
    info.setLength(5000L); // Completed

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    request.setRequestURI("/files/test-id");
    TusServletRequest tusReq = new TusServletRequest(request, true);

    me.desair.tus.server.rufh.HttpProblemDetails problemDetails =
        protocol.handleError(
            HttpMethod.PATCH,
            tusReq,
            new TusServletResponse(errResponse),
            storageService,
            lockingService,
            null,
            ProtocolVersion.RUFH,
            new me.desair.tus.server.exception.UploadAlreadyCompletedException(
                "The upload resource is already completed and cannot be modified"));
    if (problemDetails != null) {
      problemDetails.writeTo(new TusServletResponse(errResponse));
    }

    assertThat(errResponse.getStatus(), is(400));
    assertThat(errResponse.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(
        errResponse.getContentAsString(),
        is(
            "{\"type\":\"https://iana.org/assignments/http-problem-types#completed-upload\","
                + "\"title\":\"Upload Completed\","
                + "\"status\":400,"
                + "\"detail\":\"The upload resource is already completed and cannot be modified\"}"));
  }

  /**
   * Appendix B (Draft Version Identification) of draft-11: "Server implementations of draft
   * versions of the protocol send a header field Upload-Draft with the interop version."
   */
  @Test
  public void testUploadDraftHeaderInPatchResponse() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("data".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(new UploadId("test-id"));
    updated.setOffset(1004L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    protocol.process(
        HttpMethod.PATCH,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
  }

  /**
   * Section 5.1 & 5.2 (Complete Upload Append): "If the Upload-Complete request header field is set
   * to true... the server completes the upload and acknowledges the final request with a 200 OK
   * status code."
   */
  @Test
  public void testUploadAppendCompleteSuccess() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("final chunk".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(new UploadId("test-id"));
    updated.setOffset(1011L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

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
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1011"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  /**
   * Section 5.2 (Server Behavior - Offset Mismatch): "If the Upload-Offset request header field
   * value does not match the current offset... the upload resource MUST reject the request with a
   * 409 (Conflict) status code."
   */
  @Test(expected = TusException.class)
  public void testUploadAppendMismatchingOffsetThrows409() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L); // Actual offset is 1000

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 5.2 (Server Behavior - Completed Upload Reject): "If the upload is already complete...
   * the server MUST NOT modify the upload resource and MUST reject the request."
   */
  @Test(expected = TusException.class)
  public void testUploadAppendToAlreadyCompletedUploadRejected() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(5000L);
    info.setLength(5000L); // Completed

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 5.1 (Upload Append - Payload Limits): "The server MAY enforce limits on the size of
   * individual data append requests."
   */
  @Test(expected = TusException.class)
  public void testUploadAppendExceedingMaxAppendSizeThrows413() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.setContent("This append payload is 32 bytes long".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.getMaxAppendSize()).thenReturn(10L); // Max append limit 10 bytes

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 5.1 (Upload Append - Payload Limits): "Data append requests within the maximum size
   * limit are accepted."
   */
  @Test
  public void testUploadAppendWithinMaxAppendSizeSuccess() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.setContent("short payload".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.getMaxAppendSize()).thenReturn(100L); // Max append limit 100 bytes

    protocol.validate(
        HttpMethod.PATCH, request, storageService, lockingService, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 5.2 (Upload Append - Streaming & Lock Registration): Tests that during PATCH append
   * streaming, the input stream is wrapped in an InterruptibleInputStream and registered with the
   * UploadLockingService to support lock contention resolution.
   */
  @Test
  public void testUploadAppendRegistersInterruptibleStreamWithLockingService() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/test-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("chunk content".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(1000L);
    info.setLength(5000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(new UploadId("test-id"));
    updated.setOffset(1013L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    protocol.process(
        HttpMethod.PATCH,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        lockingService,
        null,
        ProtocolVersion.RUFH);

    verify(lockingService)
        .registerInputStream(eq("/files/test-id"), any(InterruptibleInputStream.class));
  }
}
