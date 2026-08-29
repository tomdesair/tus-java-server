package me.desair.tus.server;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link UploadCompletionListener} and related methods in {@link
 * TusFileUploadService}.
 */
public class UploadCompletionListenerTest {

  private Path storagePath;
  private TusFileUploadService tusFileUploadService;

  @Before
  public void setUp() throws Exception {
    storagePath = Files.createTempDirectory("tus-listener-test-");
    tusFileUploadService =
        new TusFileUploadService().withStoragePath(storagePath.toString()).withUploadUri("/files");
  }

  @After
  public void tearDown() throws Exception {
    if (tusFileUploadService != null) {
      tusFileUploadService.close();
    }
    if (storagePath != null && Files.exists(storagePath)) {
      FileUtils.deleteDirectory(storagePath.toFile());
    }
  }

  @Test
  public void testRegisterAndAddListenersNullSafe() {
    TusFileUploadService service = new TusFileUploadService();
    service.withUploadCompletionListener(null);
    service.addUploadCompletionListener(null);

    AtomicInteger callCount = new AtomicInteger(0);
    UploadCompletionListener listener = (info, svc) -> callCount.incrementAndGet();

    service.withUploadCompletionListener(listener);
    service.addUploadCompletionListener(listener);

    UploadInfo completedInfo = new UploadInfo();
    completedInfo.setId(new UploadId("test-id"));
    completedInfo.setLength(100L);
    completedInfo.setOffset(100L);

    service.notifyUploadCompletionListeners(completedInfo);
    assertEquals(2, callCount.get());
  }

  @Test
  public void testGetUploadedBytesAndTerminateByUploadInfo() throws Exception {
    UploadStorageService mockStorage = mock(UploadStorageService.class);
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("abc-123"));

    InputStream mockStream =
        new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
    when(mockStorage.getUploadedBytes(info.getId())).thenReturn(mockStream);

    TusFileUploadService service = new TusFileUploadService().withUploadStorageService(mockStorage);

    assertNull(service.getUploadedBytes((UploadInfo) null));
    UploadInfo nullIdInfo = new UploadInfo();
    assertNull(service.getUploadedBytes(nullIdInfo));

    InputStream result = service.getUploadedBytes(info);
    assertNotNull(result);
    assertEquals("hello world", IOUtils.toString(result, StandardCharsets.UTF_8));

    service.deleteUpload((UploadInfo) null);
    service.deleteUpload(info);
    verify(mockStorage, times(1)).terminateUpload(info);
  }

  @Test
  public void testListenerReceivesServiceInstanceAndReadsBytes() throws Exception {
    AtomicReference<TusFileUploadService> receivedService = new AtomicReference<>();
    AtomicReference<UploadInfo> receivedInfo = new AtomicReference<>();
    AtomicBoolean bytesReadMatch = new AtomicBoolean(false);

    byte[] payload = "completed-test-payload".getBytes(StandardCharsets.UTF_8);

    tusFileUploadService.withUploadCompletionListener(
        (info, svc) -> {
          receivedInfo.set(info);
          receivedService.set(svc);
          try (InputStream is = svc.getUploadedBytes(info)) {
            byte[] readBytes = IOUtils.toByteArray(is);
            bytesReadMatch.set(
                new String(readBytes, StandardCharsets.UTF_8).equals("completed-test-payload"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    // 1. Create upload via POST
    MockHttpServletRequest createRequest = new MockHttpServletRequest("POST", "/files");
    createRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    createRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(payload.length));
    MockHttpServletResponse createResponse = new MockHttpServletResponse();

    UploadInfo createdInfo = tusFileUploadService.process(createRequest, createResponse);
    assertNotNull(createdInfo);
    assertNull(receivedInfo.get()); // Incomplete, should not have fired

    String location = createResponse.getHeader(HttpHeader.LOCATION);
    assertNotNull(location);

    // 2. Upload full payload via PATCH
    MockHttpServletRequest patchRequest = new MockHttpServletRequest("PATCH", location);
    patchRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    patchRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    patchRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    patchRequest.setContent(payload);
    MockHttpServletResponse patchResponse = new MockHttpServletResponse();

    UploadInfo patchedInfo = tusFileUploadService.process(patchRequest, patchResponse);
    assertNotNull(patchedInfo);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, patchResponse.getStatus());

    // Verify listener was called with correct arguments
    assertNotNull(receivedInfo.get());
    assertEquals(createdInfo.getId(), receivedInfo.get().getId());
    assertEquals(tusFileUploadService, receivedService.get());
    assertTrue(bytesReadMatch.get());
  }

  @Test
  public void testListenerExceptionIsolation() throws Exception {
    AtomicInteger listenerTwoCallCount = new AtomicInteger(0);

    tusFileUploadService
        .withUploadCompletionListener(
            (info, svc) -> {
              throw new RuntimeException("Downstream failure in listener 1");
            })
        .addUploadCompletionListener(
            (info, svc) -> {
              listenerTwoCallCount.incrementAndGet();
            });

    byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

    // Creation with upload (single request completion)
    MockHttpServletRequest postRequest = new MockHttpServletRequest("POST", "/files");
    postRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    postRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(payload.length));
    postRequest.addHeader(HttpHeader.CONTENT_LENGTH, String.valueOf(payload.length));
    postRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    postRequest.setContent(payload);
    MockHttpServletResponse postResponse = new MockHttpServletResponse();

    UploadInfo info = tusFileUploadService.process(postRequest, postResponse);
    assertNotNull(info);
    assertEquals(HttpServletResponse.SC_CREATED, postResponse.getStatus());
    assertEquals(1, listenerTwoCallCount.get());
  }

  @Test
  public void testProcessReturnsNullOnOptionsAndError() throws Exception {
    MockHttpServletRequest optionsRequest = new MockHttpServletRequest("OPTIONS", "/files");
    optionsRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    MockHttpServletResponse optionsResponse = new MockHttpServletResponse();

    UploadInfo optionsInfo = tusFileUploadService.process(optionsRequest, optionsResponse);
    assertNull(optionsInfo);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, optionsResponse.getStatus());

    // Invalid PATCH request (non-existent upload)
    MockHttpServletRequest badPatchRequest =
        new MockHttpServletRequest("PATCH", "/files/non-existent-id");
    badPatchRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    badPatchRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    badPatchRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    MockHttpServletResponse badPatchResponse = new MockHttpServletResponse();

    UploadInfo badPatchInfo = tusFileUploadService.process(badPatchRequest, badPatchResponse);
    assertNull(badPatchInfo);
    assertEquals(HttpServletResponse.SC_NOT_FOUND, badPatchResponse.getStatus());
  }

  @Test
  public void testNotifyUploadCompletionListenersNullSafe() {
    TusFileUploadService service = new TusFileUploadService();
    // Verify no exception on null or empty
    service.notifyUploadCompletionListeners(null);

    UploadInfo info = new UploadInfo();
    service.notifyUploadCompletionListeners(info);
  }

  @Test
  public void testIncompletePatchDoesNotTriggerListener() throws Exception {
    AtomicInteger listenerCallCount = new AtomicInteger(0);
    tusFileUploadService.withUploadCompletionListener(
        (info, svc) -> listenerCallCount.incrementAndGet());

    // 1. Create upload of length 20
    MockHttpServletRequest createRequest = new MockHttpServletRequest("POST", "/files");
    createRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    createRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "20");
    MockHttpServletResponse createResponse = new MockHttpServletResponse();
    UploadInfo createdInfo = tusFileUploadService.process(createRequest, createResponse);
    assertNotNull(createdInfo);
    assertEquals(0, listenerCallCount.get());

    String location = createResponse.getHeader(HttpHeader.LOCATION);

    // 2. Upload first 10 bytes via PATCH (partial chunk)
    MockHttpServletRequest patch1 = new MockHttpServletRequest("PATCH", location);
    patch1.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    patch1.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    patch1.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    patch1.setContent("0123456789".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse patchResponse1 = new MockHttpServletResponse();
    UploadInfo patchInfo1 = tusFileUploadService.process(patch1, patchResponse1);
    assertNotNull(patchInfo1);
    assertEquals(Long.valueOf(10L), patchInfo1.getOffset());
    assertEquals(0, listenerCallCount.get());

    // 3. Send HEAD request - should return upload info but not trigger listener
    MockHttpServletRequest headRequest = new MockHttpServletRequest("HEAD", location);
    headRequest.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    MockHttpServletResponse headResponse = new MockHttpServletResponse();
    UploadInfo headInfo = tusFileUploadService.process(headRequest, headResponse);
    assertNotNull(headInfo);
    assertEquals(0, listenerCallCount.get());

    // 4. Upload remaining 10 bytes via PATCH - should trigger listener exactly once
    MockHttpServletRequest patch2 = new MockHttpServletRequest("PATCH", location);
    patch2.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    patch2.addHeader(HttpHeader.UPLOAD_OFFSET, "10");
    patch2.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    patch2.setContent("abcdefghij".getBytes(StandardCharsets.UTF_8));
    MockHttpServletResponse patchResponse2 = new MockHttpServletResponse();
    UploadInfo patchInfo2 = tusFileUploadService.process(patch2, patchResponse2);
    assertNotNull(patchInfo2);
    assertEquals(1, listenerCallCount.get());

    // 5. Send subsequent HEAD request on completed upload - must NOT trigger listener again
    MockHttpServletRequest headAfterComplete = new MockHttpServletRequest("HEAD", location);
    headAfterComplete.addHeader(HttpHeader.TUS_RESUMABLE, TusFileUploadService.TUS_API_VERSION);
    MockHttpServletResponse headAfterResponse = new MockHttpServletResponse();
    UploadInfo headAfterInfo = tusFileUploadService.process(headAfterComplete, headAfterResponse);
    assertNotNull(headAfterInfo);
    assertEquals(1, listenerCallCount.get());
  }

  @Test(expected = IOException.class)
  public void testCheckWasInProgressWhenStorageThrows() throws Exception {
    UploadStorageService mockStorage = mock(UploadStorageService.class);
    when(mockStorage.getUploadUri()).thenReturn("/files");
    when(mockStorage.getUploadInfo(anyString(), any()))
        .thenThrow(new IOException("Storage failure"));

    TusFileUploadService service = new TusFileUploadService().withUploadStorageService(mockStorage);
    MockHttpServletRequest patchRequest = new MockHttpServletRequest("PATCH", "/files/some-id");

    // Should catch exception gracefully and not propagate
    UploadInfo info = service.process(patchRequest, new MockHttpServletResponse());
    assertNull(info);
  }
}
