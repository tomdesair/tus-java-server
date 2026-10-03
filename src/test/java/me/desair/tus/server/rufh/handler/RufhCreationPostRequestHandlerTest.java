package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhCreationPostRequestHandlerTest {

  private RufhCreationPostRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;
  @Mock private UploadLockingService lockingService;

  @Before
  public void setUp() {
    handler = new RufhCreationPostRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();

    when(storageService.getUploadUri()).thenReturn("/files");
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.POST));
    assertTrue(handler.supports(HttpMethod.PUT));
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertFalse(handler.supports(HttpMethod.GET));
  }

  /**
   * Section 4.2.2 (Upload Creation - Server Behavior): "If the Upload-Complete header field is set
   * to false... the server MUST include the Location response header field pointing to the upload
   * resource... Servers are RECOMMENDED to use the 201 (Created) status code."
   */
  @Test
  public void testProcessPartialUploadCreation() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  /**
   * Section 4.2.2 (Upload Creation - Streaming & Lock Registration): Tests that the creation
   * request input stream is wrapped and registered for lock contention resolution.
   */
  @Test
  public void testProcessCreationRegistersInputStream() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("creation body".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    TusServletRequest tusRequest = new TusServletRequest(request);

    handler.process(
        HttpMethod.POST,
        tusRequest,
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    ArgumentCaptor<InterruptibleInputStream> captor =
        ArgumentCaptor.forClass(InterruptibleInputStream.class);
    verify(lockingService).registerInputStream(eq("/files/creation-id"), captor.capture());
    assertNotNull(captor.getValue());
  }

  @Test
  public void testProcessExistingUploadPatchReturnsEarly() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/existing-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("existing-id"));
    when(storageService.getUploadInfo("/files/existing-id", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    // Early return, default status should remain (200) and no location header set
    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.LOCATION), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessWithoutLockingServiceAndAppendReturnsNull() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.setContent("creation body".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    // storageService.append returns null
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(null);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("0"));
  }

  @Test
  public void testProcessPatchCreationWhenUploadDoesNotExist() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/does-not-exist");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);

    when(storageService.getUploadInfo("/files/does-not-exist", "owner")).thenReturn(null);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
  }

  @Test
  public void testProcessNegativeLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "-500");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("neg-id"));
    info.setOffset(0L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/neg-id"));
  }

  @Test
  public void testProcessNullInputStreamOrZeroContentLengthAndFinishedState() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    // Content length is 0
    request.setContent(new byte[0]);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setOffset(1000L);
    info.setLength(1000L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    // Since UPLOAD_COMPLETE is ?1 (true), it should set status to 200
    assertThat(response.getStatus(), is(200));
  }

  @Test
  public void testProcessBaseUriEndsWithSlashAndNullInputStreamWithContentLength()
      throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);

    // baseUri ends with slash
    when(storageService.getUploadUri()).thenReturn("/files/");
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    // Create a custom request where input stream is null but content length > 0
    TusServletRequest customRequest =
        new TusServletRequest(request) {
          @Override
          public java.io.InputStream getContentInputStream() {
            return null;
          }

          @Override
          public long getContentLengthLong() {
            return 100L;
          }
        };

    handler.process(
        HttpMethod.POST,
        customRequest,
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
  }

  @Test
  public void testProcessFinishedStateWithUploadCompleted() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0"); // false, but offset == length below

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setOffset(1000L);
    info.setLength(1000L); // Completed

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessWithPreCreatedUploadInfo() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.addHeader(HttpHeader.CONTENT_LENGTH, "100");

    UploadInfo preCreated = new UploadInfo();
    preCreated.setId(new UploadId("pre-created-id"));
    preCreated.setLength(100L);

    request.setAttribute("me.desair.tus.preCreatedUploadInfo", preCreated);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/pre-created-id"));
    verify(storageService).update(preCreated);
    assertThat(preCreated.getLength(), is(100L));
  }

  @Test
  public void testProcessWithPreCreatedUploadInfoWithoutLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");

    UploadInfo preCreated = new UploadInfo();
    preCreated.setId(new UploadId("pre-created-id"));
    preCreated.setLength(50L);

    request.setAttribute("me.desair.tus.preCreatedUploadInfo", preCreated);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/pre-created-id"));
    verify(storageService).update(preCreated);
    assertThat(preCreated.getLength(), is(50L));
  }

  @Test
  public void testProcessWithPreCreatedUploadInfoNegativeLength() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "-1");

    UploadInfo preCreated = new UploadInfo();
    preCreated.setId(new UploadId("pre-created-id"));
    preCreated.setLength(50L);

    request.setAttribute("me.desair.tus.preCreatedUploadInfo", preCreated);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/pre-created-id"));
    verify(storageService).update(preCreated);
    assertThat(preCreated.getLength(), is(50L));
  }

  @Test
  public void testProcessPartialUploadCreationWithAbsoluteUploadUri() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/files");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(
        response.getHeader(HttpHeader.LOCATION),
        is("https://upload.example.com/files/creation-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  @Test
  public void testProcessCompletedUploadCreationWithAbsoluteUploadUri() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/files");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("completed-id"));
    info.setLength(100L);
    info.setOffset(100L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(200));
    assertThat(
        response.getHeader(HttpHeader.LOCATION),
        is("https://upload.example.com/files/completed-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("100"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  /**
   * §4.1.4: "This limit does not apply to upload creation requests with no content, or to requests
   * completing the upload by including the Upload-Complete: ?1 header field."
   *
   * <p>§4.2.1: "If the upload length is not known when creating the upload resource, the
   * Upload-Length header field is omitted, and the length is deferred... In subsequent requests,
   * the upload length can be indicated by including the Upload-Length header field or by completing
   * the upload using the Upload-Complete: ?1 header field."
   */
  @Test
  public void testProcessCreationWithUploadCompleteAndContentLengthSetsAnnouncedLength()
      throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    byte[] content = "hello world".getBytes();
    request.setContent(content);

    ArgumentCaptor<UploadInfo> captor = ArgumentCaptor.forClass(UploadInfo.class);
    UploadInfo createdInfo = new UploadInfo();
    createdInfo.setId(new UploadId("complete-no-length-id"));
    createdInfo.setLength((long) content.length);
    createdInfo.setOffset((long) content.length);

    when(storageService.create(captor.capture(), nullable(String.class))).thenReturn(createdInfo);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(createdInfo);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(captor.getValue().getLength(), is((long) content.length));
    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is(String.valueOf(content.length)));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessCreationWithChunkedTransferEncodingCallsAppend() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    byte[] content = "chunked stream data".getBytes();
    // Do not set content via setContent to keep Content-Length at -1, provide via InputStream
    request.setContent(content);

    // Custom request to simulate chunked request without Content-Length header (cl < 0)
    TusServletRequest tusRequest =
        new TusServletRequest(request) {
          @Override
          public long getContentLengthLong() {
            return -1L;
          }

          @Override
          public InputStream getContentInputStream() {
            return new java.io.ByteArrayInputStream(content);
          }
        };

    UploadInfo createdInfo = new UploadInfo();
    createdInfo.setId(new UploadId("chunked-id"));
    createdInfo.setOffset(0L);

    UploadInfo appendedInfo = new UploadInfo();
    appendedInfo.setId(new UploadId("chunked-id"));
    appendedInfo.setOffset((long) content.length);

    when(storageService.create(any(UploadInfo.class), nullable(String.class)))
        .thenReturn(createdInfo);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(appendedInfo);

    handler.process(
        HttpMethod.POST,
        tusRequest,
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    verify(storageService).append(eq(createdInfo), any(InputStream.class));
    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is(String.valueOf(content.length)));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  @Test
  public void testProcessCreationWithBodyInterrupted() throws Exception {
    byte[] content = "partial-creation-data".getBytes();
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.setContent(content);

    UploadInfo createdInfo = new UploadInfo();
    createdInfo.setId(new UploadId("interrupted-create-id"));
    createdInfo.setOffset(0L);
    createdInfo.setLength(1000L);

    UploadInfo refreshedInfo = new UploadInfo();
    refreshedInfo.setId(new UploadId("interrupted-create-id"));
    refreshedInfo.setOffset(500L);
    refreshedInfo.setLength(1000L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class)))
        .thenReturn(createdInfo);
    when(storageService.getUploadInfo("/files/interrupted-create-id", "owner"))
        .thenReturn(refreshedInfo);

    when(storageService.append(any(UploadInfo.class), any()))
        .thenAnswer(
            invocation -> {
              Object stream = invocation.getArgument(1);
              if (stream instanceof InterruptibleInputStream) {
                ((InterruptibleInputStream) stream).interrupt();
              }
              throw new IOException(
                  "Stream was interrupted by the upload locking service watchdog");
            });

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("500"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  @Test(expected = IOException.class)
  public void testProcessCreationWithBodyUninterruptedIoException() throws Exception {
    byte[] content = "creation-data".getBytes();
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.setContent(content);

    UploadInfo createdInfo = new UploadInfo();
    createdInfo.setId(new UploadId("fail-create-id"));
    createdInfo.setOffset(0L);
    createdInfo.setLength(1000L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class)))
        .thenReturn(createdInfo);

    when(storageService.append(any(UploadInfo.class), any()))
        .thenThrow(new IOException("Disk write failure"));

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);
  }
}
