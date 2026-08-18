package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.io.IOException;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Test;

public class TusFileUploadServiceTest {

  @Test
  public void testAcquireUploadLockInterrupted() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"));

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    // Interrupt the thread to trigger InterruptedException during sleep
    Thread.currentThread().interrupt();

    try {
      service.acquireUploadLock(HttpMethod.HEAD, "/files/test");
      fail("Expected IOException due to thread interruption");
    } catch (IOException e) {
      // Clear interrupted flag so it doesn't leak to other tests
      Thread.interrupted();
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void testAcquireUploadLockFallback() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);

    // We throw exception 25 times and then succeed.
    // To avoid waiting 5 seconds (25 * 200ms) in the test, we mock Thread.sleep by interrupting
    // inside the mock,
    // but wait, mockLockingService doesn't run sleep. Sleep runs in the service itself.
    // Instead of doing 25 times which takes 5 seconds, let's just do it. 5 seconds is perfectly
    // fine for a fallback test.
    var stubbing = when(mockLockingService.lockUploadByUri(anyString()));
    for (int i = 0; i < 25; i++) {
      stubbing = stubbing.thenThrow(new UploadAlreadyLockedException("Locked"));
    }
    stubbing.thenReturn(mockLock);

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    UploadLock lock = service.acquireUploadLock(HttpMethod.HEAD, "/files/test");
    assertNotNull(lock);
  }

  @Test
  public void testAcquireUploadLockPatchThrowsImmediately() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"));

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    try {
      service.acquireUploadLock(HttpMethod.PATCH, "/files/test");
      fail("Expected UploadAlreadyLockedException");
    } catch (UploadAlreadyLockedException e) {
      // expected
    }
  }

  @Test
  public void testAcquireUploadLockDeleteInterrupted() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"));

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    // Interrupt the thread to trigger InterruptedException during sleep
    Thread.currentThread().interrupt();

    try {
      service.acquireUploadLock(HttpMethod.DELETE, "/files/test");
      fail("Expected IOException due to thread interruption");
    } catch (IOException e) {
      // Clear interrupted flag so it doesn't leak to other tests
      Thread.interrupted();
      assertNotNull(e.getCause());
    }
  }

  @Test
  public void testAcquireUploadLockDeleteFallback() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);

    var stubbing = when(mockLockingService.lockUploadByUri(anyString()));
    for (int i = 0; i < 25; i++) {
      stubbing = stubbing.thenThrow(new UploadAlreadyLockedException("Locked"));
    }
    stubbing.thenReturn(mockLock);

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    UploadLock lock = service.acquireUploadLock(HttpMethod.DELETE, "/files/test");
    assertNotNull(lock);
  }

  @Test
  public void testProcessSuccess() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);
    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);

    jakarta.servlet.http.HttpServletRequest mockReq =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    jakarta.servlet.http.HttpServletResponse mockResp =
        mock(jakarta.servlet.http.HttpServletResponse.class);
    when(mockReq.getMethod()).thenReturn("PATCH");
    when(mockReq.getRequestURI()).thenReturn("/files/test");
    when(mockReq.getHeader(anyString())).thenReturn("");

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    service.withUploadStorageService(mockStorage);
    when(mockStorage.getUploadInfo(anyString(), anyString())).thenReturn(null);

    service.process(mockReq, mockResp, "owner");

    verify(mockLockingService, times(1)).lockUploadByUri("/files/test");
    verify(mockLock, times(1)).close();
  }

  @Test
  public void testProcessLockFailure() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"));

    jakarta.servlet.http.HttpServletRequest mockReq =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    jakarta.servlet.http.HttpServletResponse mockResp =
        mock(jakarta.servlet.http.HttpServletResponse.class);
    when(mockReq.getMethod()).thenReturn("PATCH");
    when(mockReq.getRequestURI()).thenReturn("/files/test");

    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    service.process(mockReq, mockResp, "owner");

    verify(mockResp, times(1)).sendError(423, "Locked");
  }

  @Test
  public void testProtocolVersionConfiguration() {
    TusFileUploadService service = new TusFileUploadService();
    assertThat(
        service.getSupportedProtocolVersion(), org.hamcrest.CoreMatchers.is(ProtocolVersion.AUTO));

    service.withSupportedProtocolVersions(ProtocolVersion.RUFH);
    assertThat(
        service.getSupportedProtocolVersion(), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));

    service.withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0);
    assertThat(
        service.getSupportedProtocolVersion(),
        org.hamcrest.CoreMatchers.is(ProtocolVersion.TUS_1_0_0));

    service.withSupportedProtocolVersions(null);
    assertThat(
        service.getSupportedProtocolVersion(),
        org.hamcrest.CoreMatchers.is(ProtocolVersion.TUS_1_0_0));
  }

  @Test
  public void testDetectProtocolVersion() {
    TusFileUploadService service = new TusFileUploadService();

    // Forced TUS_1_0_0
    service.withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0);
    assertThat(
        service.detectProtocolVersion(null),
        org.hamcrest.CoreMatchers.is(ProtocolVersion.TUS_1_0_0));

    // Forced RUFH
    service.withSupportedProtocolVersions(ProtocolVersion.RUFH);
    assertThat(
        service.detectProtocolVersion(null), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));

    // AUTO mode
    service.withSupportedProtocolVersions(ProtocolVersion.AUTO);
    org.springframework.mock.web.MockHttpServletRequest req =
        new org.springframework.mock.web.MockHttpServletRequest();
    assertThat(
        service.detectProtocolVersion(req),
        org.hamcrest.CoreMatchers.is(ProtocolVersion.TUS_1_0_0));

    req.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    assertThat(
        service.detectProtocolVersion(req),
        org.hamcrest.CoreMatchers.is(ProtocolVersion.TUS_1_0_0));

    req = new org.springframework.mock.web.MockHttpServletRequest();
    req.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    assertThat(
        service.detectProtocolVersion(req), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));

    req = new org.springframework.mock.web.MockHttpServletRequest();
    req.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    assertThat(
        service.detectProtocolVersion(req), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));

    req = new org.springframework.mock.web.MockHttpServletRequest();
    req.addHeader(HttpHeader.UPLOAD_DRAFT, "4");
    assertThat(
        service.detectProtocolVersion(req), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));

    req = new org.springframework.mock.web.MockHttpServletRequest();
    req.addHeader("upload-draft-interop-version", "4");
    assertThat(
        service.detectProtocolVersion(req), org.hamcrest.CoreMatchers.is(ProtocolVersion.RUFH));
  }

  @Test
  public void testWithMaxAppendSize() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMaxAppendSize(1024L);
    assertThat(service.getUploadStorageService().getMaxAppendSize(), is(1024L));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithMaxAppendSizeInvalid() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMaxAppendSize(0L);
  }

  @Test
  public void testWithMinAppendSize() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMinAppendSize(512L);
    assertThat(service.getUploadStorageService().getMinAppendSize(), is(512L));

    service.withMinAppendSize(null);
    org.junit.Assert.assertNull(service.getUploadStorageService().getMinAppendSize());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithMinAppendSizeInvalid() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMinAppendSize(0L);
  }

  @Test
  public void testWithMinSize() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMinSize(2048L);
    assertThat(service.getUploadStorageService().getMinSize(), is(2048L));

    service.withMinSize(null);
    org.junit.Assert.assertNull(service.getUploadStorageService().getMinSize());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithMinSizeInvalid() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMinSize(0L);
  }

  @Test
  public void testWithUploadStorageServicePreservesConfiguration() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMaxUploadSize(10000L);
    service.withMaxAppendSize(1024L);
    service.withMinAppendSize(512L);
    service.withMinSize(2048L);
    service.withUploadDeduplication(true);

    UploadStorageService newStorage = mock(UploadStorageService.class);
    service.withUploadStorageService(newStorage);

    verify(newStorage).setMaxUploadSize(10000L);
    verify(newStorage).setMaxAppendSize(1024L);
    verify(newStorage).setMinAppendSize(512L);
    verify(newStorage).setMinSize(2048L);
    verify(newStorage).setUploadDeduplicationEnabled(true);
  }

  @Test
  public void testThreadLocalCacheDelegatesAppendAndMinSizes() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMaxAppendSize(1024L);
    service.withMinAppendSize(512L);
    service.withMinSize(2048L);
    service.withThreadLocalCache(true);

    assertThat(service.getUploadStorageService().getMaxAppendSize(), is(1024L));
    assertThat(service.getUploadStorageService().getMinAppendSize(), is(512L));
    assertThat(service.getUploadStorageService().getMinSize(), is(2048L));
  }

  @Test
  public void testProtocolVersionGetName() {
    assertThat(ProtocolVersion.TUS_1_0_0.getName(), is("TUS-1.0.0"));
    assertThat(ProtocolVersion.RUFH.getName(), is("RUFH"));
    assertThat(ProtocolVersion.AUTO.getName(), is("AUTO"));
  }

  @Test
  public void testProcessTusExceptionRufhOffsetMismatch() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);
    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);

    UploadStorageService mockStorage = mock(UploadStorageService.class);
    UploadInfo info = new UploadInfo();
    info.setOffset(100L);
    when(mockStorage.getUploadInfo(anyString(), any())).thenReturn(info);

    org.springframework.mock.web.MockHttpServletRequest mockReq =
        new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpServletResponse mockResp =
        new org.springframework.mock.web.MockHttpServletResponse();

    mockReq.setMethod("PATCH");
    mockReq.setRequestURI("/files/test");
    mockReq.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    mockReq.addHeader(HttpHeader.UPLOAD_OFFSET, "200");
    mockReq.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorage)
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    service.process(mockReq, mockResp, "owner");

    assertThat(mockResp.getStatus(), is(409));
    assertThat(
        mockResp.getHeader(HttpHeader.CONTENT_TYPE), is(HttpHeader.CONTENT_TYPE_PROBLEM_JSON));
  }

  @Test
  public void testProcessTusExceptionRufhNullInfoAndHeader() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);
    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);

    UploadStorageService mockStorage = mock(UploadStorageService.class);
    UploadInfo info = new UploadInfo();
    // info with null offset
    when(mockStorage.getUploadInfo(anyString(), any())).thenReturn(info);

    org.springframework.mock.web.MockHttpServletRequest mockReq =
        new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpServletResponse mockResp =
        new org.springframework.mock.web.MockHttpServletResponse();

    mockReq.setMethod("PATCH");
    mockReq.setRequestURI("/files/test");
    mockReq.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    mockReq.addHeader(HttpHeader.UPLOAD_OFFSET, "200");
    mockReq.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorage)
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    service.process(mockReq, mockResp, "owner");

    assertThat(mockResp.getStatus(), is(409));
    assertThat(
        mockResp.getHeader(HttpHeader.CONTENT_TYPE), is(HttpHeader.CONTENT_TYPE_PROBLEM_JSON));
  }

  @Test
  public void testProcessTusExceptionRufhNon409() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);
    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);

    UploadStorageService mockStorage = mock(UploadStorageService.class);
    UploadInfo info = new UploadInfo();
    when(mockStorage.getUploadInfo(anyString(), any())).thenReturn(info);

    org.springframework.mock.web.MockHttpServletRequest mockReq =
        new org.springframework.mock.web.MockHttpServletRequest();
    org.springframework.mock.web.MockHttpServletResponse mockResp =
        new org.springframework.mock.web.MockHttpServletResponse();

    mockReq.setMethod("PATCH");
    mockReq.setRequestURI("/files/test");
    mockReq.addHeader(HttpHeader.CONTENT_TYPE, "text/plain");
    mockReq.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorage)
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    service.process(mockReq, mockResp, "owner");

    assertThat(mockResp.getStatus(), is(415));
  }

  @Test
  public void testProcessTusExceptionResponseCommitted() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);
    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);

    UploadStorageService mockStorage = mock(UploadStorageService.class);
    UploadInfo info = new UploadInfo();
    when(mockStorage.getUploadInfo(anyString(), any())).thenReturn(info);

    org.springframework.mock.web.MockHttpServletRequest mockReq =
        new org.springframework.mock.web.MockHttpServletRequest();
    jakarta.servlet.http.HttpServletResponse mockResp =
        mock(jakarta.servlet.http.HttpServletResponse.class);
    when(mockResp.isCommitted()).thenReturn(true);

    mockReq.setMethod("PATCH");
    mockReq.setRequestURI("/files/test");
    // Cause a validation error (415)
    mockReq.addHeader(HttpHeader.CONTENT_TYPE, "text/plain");

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorage)
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    service.process(mockReq, mockResp, "owner");

    // Since response is committed, sendError should not be called
    verify(mockResp, never()).sendError(anyInt(), anyString());
  }

  @Test
  public void testDisableCreationWithUploadWhenCreationDisabled() throws Exception {
    TusFileUploadService service = new TusFileUploadService();
    // Disable creation extension first so it is not present
    service.disableTusExtension("creation");

    // Disable creation-with-upload should not fail or throw ClassCastException/NullPointerException
    try {
      service.disableTusExtension("creation-with-upload");
    } catch (Exception e) {
      fail(
          "Should not throw exception when disabling creation-with-upload when creation is not"
              + " enabled");
    }
  }

  @Test
  public void testGetRawInterimResponse() throws Exception {
    TusFileUploadService service =
        new TusFileUploadService()
            .withSupportedProtocolVersions(ProtocolVersion.RUFH)
            .withUploadUri("/files");

    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    String raw = service.getRawInterimResponse(request, "owner-123");
    assertNotNull(raw);
    org.junit.Assert.assertTrue(raw.startsWith("HTTP/1.1 104 Upload Resumption Supported\r\n"));
    org.junit.Assert.assertTrue(raw.contains("Location: /files/"));
    org.junit.Assert.assertTrue(raw.contains("Upload-Offset: 0"));

    // Test non-matching request (e.g. GET) returns null
    request.setMethod("GET");
    org.junit.Assert.assertNull(service.getRawInterimResponse(request, "owner-123"));

    // Test AUTO protocol service with TUS 1.0.0 request returns null for RUFH 104 interim response
    TusFileUploadService autoService =
        new TusFileUploadService()
            .withSupportedProtocolVersions(ProtocolVersion.AUTO)
            .withUploadUri("/files");
    org.springframework.mock.web.MockHttpServletRequest tus10Request =
        new org.springframework.mock.web.MockHttpServletRequest();
    tus10Request.setMethod("POST");
    tus10Request.setRequestURI("/files");
    tus10Request.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    org.junit.Assert.assertNull(autoService.getRawInterimResponse(tus10Request, "owner-123"));

    // Test null input
    org.junit.Assert.assertNull(
        service.getRawInterimResponse((jakarta.servlet.http.HttpServletRequest) null, "owner-123"));
  }

  @Test
  public void testWithJsonSerialization() {
    TusFileUploadService service = new TusFileUploadService().withJsonSerialization();
    org.junit.Assert.assertTrue(service.getUploadStorageService().isJsonSerializationEnabled());

    service.withJsonSerialization(false);
    org.junit.Assert.assertFalse(service.getUploadStorageService().isJsonSerializationEnabled());
  }

  @Test
  public void testClose() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    TusFileUploadService service =
        new TusFileUploadService().withUploadLockingService(mockLockingService);

    service.close();

    verify(mockLockingService).close();
  }

  @Test
  public void testGetUploadInfoSingleArg() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadStorageService mockStorageService = mock(UploadStorageService.class);
    UploadLock mockLock = mock(UploadLock.class);
    UploadInfo mockInfo = new UploadInfo();

    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);
    when(mockStorageService.getUploadInfo("/files/123", null)).thenReturn(mockInfo);

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorageService);

    UploadInfo result = service.getUploadInfo("/files/123");
    assertNotNull(result);
    verify(mockStorageService).getUploadInfo("/files/123", null);
  }

  @Test
  public void testDeleteUploadSingleArg() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadStorageService mockStorageService = mock(UploadStorageService.class);
    UploadLock mockLock = mock(UploadLock.class);
    UploadInfo mockInfo = new UploadInfo();

    when(mockLockingService.lockUploadByUri(anyString())).thenReturn(mockLock);
    when(mockStorageService.getUploadInfo("/files/123", null)).thenReturn(mockInfo);

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorageService);

    service.deleteUpload("/files/123");
    verify(mockStorageService).terminateUpload(mockInfo);
  }

  @Test
  public void testWithMaxLockRetries() {
    TusFileUploadService service = new TusFileUploadService();
    assertEquals(40, service.getMaxLockRetries());

    service.withMaxLockRetries(5);
    assertEquals(5, service.getMaxLockRetries());

    service.withMaxLockRetries(0);
    assertEquals(0, service.getMaxLockRetries());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithMaxLockRetriesNegativeThrows() {
    new TusFileUploadService().withMaxLockRetries(-1);
  }

  @Test
  public void testAcquireUploadLockWithConfiguredRetries() throws Exception {
    UploadLockingService mockLockingService = mock(UploadLockingService.class);
    UploadLock mockLock = mock(UploadLock.class);

    // Throw 2 times then succeed
    when(mockLockingService.lockUploadByUri(anyString()))
        .thenThrow(new UploadAlreadyLockedException("Locked"))
        .thenThrow(new UploadAlreadyLockedException("Locked"))
        .thenReturn(mockLock);

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withMaxLockRetries(2);

    UploadLock lock = service.acquireUploadLock(HttpMethod.HEAD, "/files/test");
    assertNotNull(lock);
    verify(mockLockingService, times(2)).requestLockRelease("/files/test");
  }
}
