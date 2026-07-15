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
    assertThat(response.getHeader(HttpHeader.UPLOAD_DRAFT), is("11"));
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
}
