package me.desair.tus.server.creationwithupload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class CreationWithUploadPostRequestHandlerTest {

  private CreationWithUploadPostRequestHandler handler;

  @Before
  public void setUp() {
    handler = new CreationWithUploadPostRequestHandler();
  }

  @Test
  public void testSupports() {
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PATCH), is(false));
  }

  @Test
  public void testProcessWithLockingService() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("5");
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/123");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(10L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setLength(10L);
    updatedInfo.setOffset(5L);

    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    ArgumentCaptor<InterruptibleInputStream> captor =
        ArgumentCaptor.forClass(InterruptibleInputStream.class);
    verify(lockingService).registerInputStream(eq("/files/123"), captor.capture());
    assertNotNull(captor.getValue());
    verify(lockingService).unregisterInputStream(eq("/files/123"));
    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "5");
  }

  @Test
  public void testProcessWithLockingServiceAcquiresLockAndCleansUp() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);
    me.desair.tus.server.upload.UploadLock mockLock =
        mock(me.desair.tus.server.upload.UploadLock.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("5");
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/123");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

    me.desair.tus.server.upload.UploadId id = new me.desair.tus.server.upload.UploadId("123");
    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setId(id);
    uploadInfo.setLength(10L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setId(id);
    updatedInfo.setLength(10L);
    updatedInfo.setOffset(5L);

    when(lockingService.isLocked(id)).thenReturn(false);
    when(lockingService.lockUploadByUri("/files/123")).thenReturn(mockLock);
    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    verify(lockingService).lockUploadByUri("/files/123");
    verify(mockLock).close();
    verify(lockingService).unregisterInputStream("/files/123");
    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "5");
  }

  @Test
  public void testProcessWithoutLockingService() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("5");
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/123");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("hello".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(10L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setLength(10L);
    updatedInfo.setOffset(5L);

    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(
        HttpMethod.POST,
        request,
        response,
        storageService,
        new me.desair.tus.server.upload.NoOpUploadLockingService(),
        "owner",
        null);

    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "5");
  }

  @Test
  public void testProcessInterruptedByLockingServiceContention() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("100");
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/interrupted-cwu");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("data".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(100L);
    uploadInfo.setOffset(0L);

    UploadInfo refreshed = new UploadInfo();
    refreshed.setLength(100L);
    refreshed.setOffset(50L);

    when(storageService.getUploadInfo("/files/interrupted-cwu", "owner"))
        .thenReturn(uploadInfo)
        .thenReturn(refreshed);

    when(storageService.append(eq(uploadInfo), any(InputStream.class)))
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
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "50");
  }

  @Test(expected = IOException.class)
  public void testProcessUninterruptedIoExceptionRethrown() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("100");
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/cwu-error");
    when(request.getContentInputStream()).thenReturn(new ByteArrayInputStream("data".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(100L);
    uploadInfo.setOffset(0L);

    when(storageService.getUploadInfo("/files/cwu-error", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class)))
        .thenThrow(new IOException("S3 network error"));

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);
  }

  @Test
  public void testProcessChunkedWithContent() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn(null);
    when(request.hasContent()).thenReturn(true);
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/chunked-cwu");
    when(request.getContentInputStream())
        .thenReturn(new ByteArrayInputStream("chunked-bytes".getBytes()));

    UploadInfo uploadInfo = new UploadInfo();
    uploadInfo.setLength(20L);
    uploadInfo.setOffset(0L);

    UploadInfo updatedInfo = new UploadInfo();
    updatedInfo.setLength(20L);
    updatedInfo.setOffset(13L);

    when(storageService.getUploadInfo("/files/chunked-cwu", "owner")).thenReturn(uploadInfo);
    when(storageService.append(eq(uploadInfo), any(InputStream.class))).thenReturn(updatedInfo);

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    ArgumentCaptor<InterruptibleInputStream> captor =
        ArgumentCaptor.forClass(InterruptibleInputStream.class);
    verify(lockingService).registerInputStream(eq("/files/chunked-cwu"), captor.capture());
    assertNotNull(captor.getValue());
    verify(response).setHeader(HttpHeader.UPLOAD_OFFSET, "13");
  }

  @Test
  public void testProcessChunkedEmptyNoContent() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn(null);
    when(request.hasContent()).thenReturn(false);

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    // Verify storage append was never called for empty creation request
    org.mockito.Mockito.verifyNoInteractions(storageService);
    org.mockito.Mockito.verifyNoInteractions(lockingService);
    org.mockito.Mockito.verify(response, org.mockito.Mockito.never())
        .setHeader(eq(HttpHeader.UPLOAD_OFFSET), any());
  }

  @Test
  public void testProcessZeroContentLength() throws Exception {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);
    UploadStorageService storageService = mock(UploadStorageService.class);
    UploadLockingService lockingService = mock(UploadLockingService.class);

    when(request.getHeader(HttpHeader.CONTENT_LENGTH)).thenReturn("0");

    handler.process(
        HttpMethod.POST, request, response, storageService, lockingService, "owner", null);

    // Verify storage append was never called for 0 Content-Length
    org.mockito.Mockito.verifyNoInteractions(storageService);
    org.mockito.Mockito.verifyNoInteractions(lockingService);
  }
}
