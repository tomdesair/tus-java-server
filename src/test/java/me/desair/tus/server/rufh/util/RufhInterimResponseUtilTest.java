package me.desair.tus.server.rufh.util;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.upload.disk.DiskStorageService;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Tests for {@link RufhInterimResponseUtil}. */
public class RufhInterimResponseUtilTest {

  @Test
  public void testGetRawInterimResponse() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UuidUploadIdFactory idFactory = new UuidUploadIdFactory();
    idFactory.setUploadUri("/files");
    DiskStorageService storageService = new DiskStorageService("/tmp/tus");
    storageService.setIdFactory(idFactory);

    String raw =
        RufhInterimResponseUtil.getRawInterimResponse(request, storageService, "owner-123");
    assertNotNull(raw);
    assertTrue(raw.startsWith("HTTP/1.1 104 Upload Resumption Supported\r\n"));
    assertTrue(raw.contains("Location: /files/"));
    assertTrue(raw.contains("Upload-Offset: 0"));

    // Non-matching method returns null
    request.setMethod("GET");
    assertNull(RufhInterimResponseUtil.getRawInterimResponse(request, storageService, "owner-123"));

    // Null inputs
    assertNull(
        RufhInterimResponseUtil.getRawInterimResponse(
            (HttpServletRequest) null, storageService, "owner-123"));
    assertNull(RufhInterimResponseUtil.getRawInterimResponse(request, null, "owner-123"));
    assertNull(RufhInterimResponseUtil.getRawInterimResponse((String) null, 0L));

    // Overload method
    assertNotNull(RufhInterimResponseUtil.getRawInterimResponse("/files/123", 0L, "owner-123"));
  }

  @Test
  public void testGetRawInterimResponseWithHostHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.setScheme("https");
    request.addHeader("Host", "example.com");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UuidUploadIdFactory idFactory = new UuidUploadIdFactory();
    idFactory.setUploadUri("/files");
    DiskStorageService storageService = new DiskStorageService("/tmp/tus");
    storageService.setIdFactory(idFactory);

    String raw =
        RufhInterimResponseUtil.getRawInterimResponse(request, storageService, "owner-123");
    assertNotNull(raw);
    assertTrue(raw.contains("Location: https://example.com/files/"));
  }

  @Test
  public void testGetRawInterimResponseWithExistingUploadAndStorageException() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("PATCH");
    request.setRequestURI("/files/existing-123");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        org.mockito.Mockito.mock(me.desair.tus.server.upload.UploadStorageService.class);
    me.desair.tus.server.upload.UploadInfo existing = new me.desair.tus.server.upload.UploadInfo();

    org.mockito.Mockito.when(mockStorage.getUploadInfo("/files/existing-123", "owner"))
        .thenReturn(existing);

    String raw = RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner");
    assertNotNull(raw);
    assertTrue(raw.contains("Upload-Offset: 0"));
    org.junit.Assert.assertFalse(raw.contains("Location:"));

    // Storage exception returns null
    org.mockito.Mockito.when(mockStorage.getUploadInfo("/files/existing-123", "owner"))
        .thenThrow(new RuntimeException("Storage failure"));
    assertNull(RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner"));
  }

  @Test
  public void testGetRawInterimResponseWithExistingUploadNotFoundAndNullHost() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("PATCH");
    request.setRequestURI("/files/not-found-123");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    // Host header is not set (null host)

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        org.mockito.Mockito.mock(me.desair.tus.server.upload.UploadStorageService.class);
    org.mockito.Mockito.when(mockStorage.getUploadUri()).thenReturn("/files");
    me.desair.tus.server.upload.UploadInfo created = new me.desair.tus.server.upload.UploadInfo();
    created.setId(new me.desair.tus.server.upload.UploadId("created-456"));

    org.mockito.Mockito.when(mockStorage.getUploadInfo("/files/not-found-123", "owner"))
        .thenReturn(null);
    org.mockito.Mockito.when(
            mockStorage.create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("owner")))
        .thenReturn(created);

    String raw = RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner");
    assertNotNull(raw);
    assertTrue(raw.contains("Location: /files/created-456"));
  }

  @Test
  public void testGetRawInterimResponseWithAbsoluteUploadUri() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/files");

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        org.mockito.Mockito.mock(me.desair.tus.server.upload.UploadStorageService.class);
    me.desair.tus.server.upload.UploadInfo created = new me.desair.tus.server.upload.UploadInfo();
    created.setId(new me.desair.tus.server.upload.UploadId("123"));

    org.mockito.Mockito.when(mockStorage.getUploadUri())
        .thenReturn("https://custom.domain.com/files");
    org.mockito.Mockito.when(
            mockStorage.create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("owner")))
        .thenReturn(created);

    String raw = RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner");
    assertNotNull(raw);
    assertTrue(raw.contains("Location: https://custom.domain.com/files/123"));
  }

  @Test
  public void testGetRawInterimResponseWithNullUploadOffset() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("PATCH");
    request.setRequestURI("/files/null-offset");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        org.mockito.Mockito.mock(me.desair.tus.server.upload.UploadStorageService.class);
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setOffset(null);

    org.mockito.Mockito.when(mockStorage.getUploadInfo("/files/null-offset", "owner"))
        .thenReturn(info);

    String raw = RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner");
    assertNotNull(raw);
    assertTrue(raw.contains("Upload-Offset: 0"));
  }

  @Test
  public void testGetRawInterimResponseWithNullMethodAndPartialSchemeHost() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod(null);
    request.setRequestURI("/files");

    me.desair.tus.server.upload.UploadStorageService mockStorage =
        org.mockito.Mockito.mock(me.desair.tus.server.upload.UploadStorageService.class);
    org.mockito.Mockito.when(mockStorage.getUploadUri()).thenReturn("/files");

    assertNull(RufhInterimResponseUtil.getRawInterimResponse(request, mockStorage, "owner"));

    // Scheme set, host null
    MockHttpServletRequest request2 = new MockHttpServletRequest();
    request2.setMethod("POST");
    request2.setRequestURI("/files");
    request2.setScheme("https");

    me.desair.tus.server.upload.UploadInfo created = new me.desair.tus.server.upload.UploadInfo();
    created.setId(new me.desair.tus.server.upload.UploadId("789"));
    org.mockito.Mockito.when(
            mockStorage.create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("owner")))
        .thenReturn(created);

    String raw = RufhInterimResponseUtil.getRawInterimResponse(request2, mockStorage, "owner");
    assertNotNull(raw);
    assertTrue(raw.contains("Location: /files/789"));
  }
}
