package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
public class RufhAppendPatchRequestHandlerTest {

  private RufhAppendPatchRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;
  @Mock private UploadLockingService lockingService;

  @Before
  public void setUp() {
    handler = new RufhAppendPatchRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertFalse(handler.supports(HttpMethod.POST));
  }

  /**
   * Section 5.2 (Upload Append - Server Behavior): "If the Upload-Complete request header field is
   * set to false... the upload resource acknowledges the appended data by sending a 2xx response
   * with the Upload-Complete header field set to false."
   */
  @Test
  public void testProcessPartialAppend() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("append data".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(1000L);
    info.setLength(5000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(info.getId());
    updated.setOffset(1011L);
    updated.setLength(5000L);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    TusServletRequest tusRequest = new TusServletRequest(request);

    handler.process(
        HttpMethod.PATCH,
        tusRequest,
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1011"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));

    ArgumentCaptor<InterruptibleInputStream> captor =
        ArgumentCaptor.forClass(InterruptibleInputStream.class);
    verify(lockingService).registerInputStream(eq("/files/append-id"), captor.capture());
    assertNotNull(captor.getValue());
  }

  @Test
  public void testProcessWithNullUploadInfo() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(null);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    // Early return, default status should be kept (usually 200 for mock response, but we didn't
    // touch it)
    assertThat(response.getStatus(), is(200));
  }

  @Test
  public void testProcessWithNullInputStreamAndNoLocking() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(1000L);
    info.setLength(5000L);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);

    // Create a custom request where content input stream is null
    TusServletRequest customRequest =
        new TusServletRequest(request) {
          @Override
          public java.io.InputStream getContentInputStream() {
            return null;
          }
        };

    handler.process(
        HttpMethod.PATCH,
        customRequest,
        new TusServletResponse(response),
        storageService,
        null, // No locking service
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1000"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  @Test
  public void testProcessCallFiveParameterMethodAndAppendReturnsNull() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.addHeader(HttpHeader.CONTENT_LENGTH, "4");
    request.setContent("data".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(1000L);
    info.setLength(1004L);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    // storageService.append returns null
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(null);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(200));
    // Offset should still be 1000 because append returned null
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1000"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessIsFinishedCombinations() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "5000");
    // uploadComplete is not set or false, but upload is completed (offset == length)
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(5000L);
    info.setLength(5000L); // Completed

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(200));
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
  public void testProcessCompletingAppendOnDeferredLengthUploadSetsLength() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "100");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    byte[] content = "final-bytes".getBytes();
    request.setContent(content);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(100L);
    info.setLength(null); // Deferred length

    UploadInfo updated = new UploadInfo();
    updated.setId(info.getId());
    updated.setOffset(100L + content.length);
    updated.setLength(100L + content.length);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    verify(storageService).update(info);
    assertThat(info.getLength(), is(100L + content.length));
    assertThat(response.getStatus(), is(200));
    assertThat(
        response.getHeader(HttpHeader.UPLOAD_OFFSET), is(String.valueOf(100L + content.length)));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessCompletingAppendOnDeferredLengthUploadWithNullOffsetSetsLength()
      throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    byte[] content = "initial-and-final-bytes".getBytes();
    request.setContent(content);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(null);
    info.setLength(null); // Deferred length

    UploadInfo updated = new UploadInfo();
    updated.setId(info.getId());
    updated.setOffset((long) content.length);
    updated.setLength((long) content.length);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    verify(storageService).update(info);
    assertThat(info.getLength(), is((long) content.length));
    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is(String.valueOf(content.length)));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }

  @Test
  public void testProcessInterruptedByLockingServiceContention() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    byte[] content = "partial-data".getBytes();
    request.setContent(content);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(0L);
    info.setLength(1000L);

    UploadInfo refreshed = new UploadInfo();
    refreshed.setId(new UploadId("append-id"));
    refreshed.setOffset(500L);
    refreshed.setLength(1000L);

    when(storageService.getUploadInfo("/files/append-id", "owner"))
        .thenReturn(info)
        .thenReturn(refreshed);

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
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("500"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  @Test(expected = IOException.class)
  public void testProcessUninterruptedIoExceptionRethrown() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    byte[] content = "data".getBytes();
    request.setContent(content);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(0L);
    info.setLength(1000L);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any()))
        .thenThrow(new IOException("Storage failure"));

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);
  }

  @Test
  public void testProcessInterruptedByLockContention() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("hello world".getBytes());

    UploadInfo initial = new UploadInfo();
    initial.setId(new UploadId("append-id"));
    initial.setOffset(1000L);
    initial.setLength(2000L);

    UploadInfo refreshed = new UploadInfo();
    refreshed.setId(new UploadId("append-id"));
    refreshed.setOffset(1005L);
    refreshed.setLength(2000L);

    when(storageService.getUploadInfo("/files/append-id", "owner"))
        .thenReturn(initial)
        .thenReturn(refreshed);

    when(storageService.append(eq(initial), any(InputStream.class)))
        .thenAnswer(
            inv -> {
              Object s = inv.getArgument(1);
              if (s instanceof InterruptibleInputStream) {
                ((InterruptibleInputStream) s).interrupt();
              }
              throw new IOException("Stream interrupted by locking service contention");
            });

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1005"));
  }
}
