package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
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

    TusFileUploadService service =
        new TusFileUploadService()
            .withUploadLockingService(mockLockingService)
            .withUploadStorageService(mockStorage)
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    service.process(mockReq, mockResp, "owner");

    assertThat(mockResp.getStatus(), is(415));
  }
}
