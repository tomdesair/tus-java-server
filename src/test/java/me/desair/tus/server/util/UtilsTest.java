package me.desair.tus.server.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.Serializable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UtilsTest {

  private static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "utils-test").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Test
  public void readSerializableWithValidFile() throws Exception {
    Path testFile = storagePath.resolve("valid-" + UUID.randomUUID());
    TestSerializable original = new TestSerializable("test-value");

    Utils.writeSerializable(original, testFile);

    TestSerializable result = Utils.readSerializable(testFile, TestSerializable.class);

    assertThat(result.getValue(), is("test-value"));

    Files.deleteIfExists(testFile);
  }

  @Test
  public void readSerializableWithCorruptedFile() throws Exception {
    Path corruptedFile = storagePath.resolve("corrupted-" + UUID.randomUUID());

    // Create a corrupted file with invalid serialization data
    Files.write(corruptedFile, "this is not valid serialized data".getBytes());

    // Should return null instead of throwing an exception
    TestSerializable result = Utils.readSerializable(corruptedFile, TestSerializable.class);

    assertThat(result, is(nullValue()));

    Files.deleteIfExists(corruptedFile);
  }

  @Test
  public void readSerializableWithTruncatedFile() throws Exception {
    Path truncatedFile = storagePath.resolve("truncated-" + UUID.randomUUID());

    // Create a truncated file (partial serialization header)
    // Java serialization magic number is 0xACED, followed by version
    Files.write(truncatedFile, new byte[] {(byte) 0xAC, (byte) 0xED, 0x00});

    // Should return null instead of throwing EOFException
    TestSerializable result = Utils.readSerializable(truncatedFile, TestSerializable.class);

    assertThat(result, is(nullValue()));

    Files.deleteIfExists(truncatedFile);
  }

  @Test
  public void readSerializableWithEmptyFile() throws Exception {
    Path emptyFile = storagePath.resolve("empty-" + UUID.randomUUID());

    // Create an empty file
    Files.createFile(emptyFile);

    // Should return null instead of throwing EOFException
    TestSerializable result = Utils.readSerializable(emptyFile, TestSerializable.class);

    assertThat(result, is(nullValue()));

    Files.deleteIfExists(emptyFile);
  }

  @Test
  public void readSerializableWithNullPath() throws Exception {
    // Should return null when path is null
    TestSerializable result = Utils.readSerializable(null, TestSerializable.class);
    assertThat(result, is(nullValue()));
  }

  @Test
  public void writeSerializableWithNullPathShouldBeNoOp() throws Exception {
    // Should safely do nothing without throwing exception
    Utils.writeSerializable(new TestSerializable("test"), null);
    assertThat(Utils.readSerializable(null, TestSerializable.class), is(nullValue()));
  }

  @Test
  public void testGetHeader() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("test-header")).thenReturn(" value  ");
    when(request.getHeader("missing-header")).thenReturn(null);

    assertThat(Utils.getHeader(request, "test-header"), is("value"));
    assertThat(Utils.getHeader(request, "missing-header"), is(""));
  }

  @Test
  public void testGetLongHeader() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("long-header")).thenReturn("12345");
    when(request.getHeader("invalid-header")).thenReturn("abc");
    when(request.getHeader("missing-header")).thenReturn(null);

    assertThat(Utils.getLongHeader(request, "long-header"), is(12345L));
    assertThat(Utils.getLongHeader(request, "invalid-header"), is(nullValue()));
    assertThat(Utils.getLongHeader(request, "missing-header"), is(nullValue()));
  }

  @Test
  public void testBuildRemoteIpList() {
    HttpServletRequest request1 = mock(HttpServletRequest.class);
    when(request1.getRemoteAddr()).thenReturn("192.168.1.1");
    when(request1.getHeader(HttpHeader.X_FORWARDED_FOR)).thenReturn("10.0.0.1, 10.0.0.2");

    assertThat(Utils.buildRemoteIpList(request1), is("10.0.0.1, 10.0.0.2, 192.168.1.1"));

    HttpServletRequest request2 = mock(HttpServletRequest.class);
    when(request2.getRemoteAddr()).thenReturn("192.168.1.1");
    when(request2.getHeader(HttpHeader.X_FORWARDED_FOR)).thenReturn(null);

    assertThat(Utils.buildRemoteIpList(request2), is("192.168.1.1"));
  }

  @Test
  public void testParseConcatenationIDsFromHeader() {
    String headerValue = "final; id1 id2 id3";
    List<String> ids = Utils.parseConcatenationIDsFromHeader(headerValue);
    assertThat(ids, is(Arrays.asList("id1", "id2", "id3")));
  }

  @Test
  public void testWriteAndReadSerializable() throws Exception {
    Path tempFile = Files.createTempFile("tus-test-serializable", ".tmp");
    try {
      String expected = "Tus Test Serializable Object";
      Utils.writeSerializable(expected, tempFile);

      String actual = Utils.readSerializable(tempFile, String.class);
      assertThat(actual, is(expected));
    } finally {
      Files.deleteIfExists(tempFile);
    }

    Path relPath = Paths.get("target", "temp-serializable-rel-" + UUID.randomUUID() + ".bin");
    try {
      String expected = "Tus Test Relative Object";
      Utils.writeSerializable(expected, relPath);

      String actual = Utils.readSerializable(relPath, String.class);
      assertThat(actual, is(expected));
    } finally {
      Files.deleteIfExists(relPath);
    }
  }

  @Test
  public void testReadSerializableNullPath() throws Exception {
    assertThat(Utils.readSerializable(null, String.class), is(nullValue()));
  }

  @Test
  public void testWriteSerializableNullPath() throws Exception {
    // Should do nothing without exception
    Utils.writeSerializable("test", null);
    Utils.writeSerializable(null, Paths.get("target", "ignored.bin"));
  }

  @Test
  public void testCreateTempSiblingPath() {
    assertThat(Utils.createTempSiblingPath(null), is(nullValue()));

    Path absPath = Paths.get("target", "test-dir", "data.json").toAbsolutePath();
    Path tempAbs = Utils.createTempSiblingPath(absPath);
    assertThat(tempAbs, is(notNullValue()));
    assertThat(tempAbs.getParent(), is(absPath.getParent()));
    assertThat(tempAbs.getFileName().toString().startsWith("data.json.tmp."), is(true));

    Path relPathNoParent = Paths.get("data.json");
    Path tempRelNoParent = Utils.createTempSiblingPath(relPathNoParent);
    assertThat(tempRelNoParent, is(notNullValue()));
    assertThat(tempRelNoParent.getFileName().toString().startsWith("data.json.tmp."), is(true));
  }

  @Test
  public void testDeletePathQuietly() throws Exception {
    assertThat(Utils.deletePathQuietly(null), is(false));
    assertThat(Utils.deletePathQuietly(Paths.get("non-existent-" + UUID.randomUUID())), is(false));

    Path tempFile = Files.createTempFile("tus-delete-quietly", ".tmp");
    assertThat(Utils.deletePathQuietly(tempFile), is(true));
    assertThat(Files.exists(tempFile), is(false));
  }

  @Test
  public void testTempPathAutoCloseable() throws Exception {
    Path targetFile = Paths.get("target", "temp-target-" + UUID.randomUUID() + ".json");
    Path tempFilePath;

    try (Utils.TempPath tempPath = Utils.createTempSibling(targetFile)) {
      tempFilePath = tempPath.getPath();
      assertThat(tempFilePath, is(notNullValue()));
      Files.write(tempFilePath, "temp data".getBytes());
      assertThat(Files.exists(tempFilePath), is(true));
    }

    // AutoCloseable should have automatically deleted the temp file
    assertThat(Files.exists(tempFilePath), is(false));

    // Null path handling
    try (Utils.TempPath nullTempPath = new Utils.TempPath(null)) {
      assertThat(nullTempPath.getPath(), is(nullValue()));
    }
  }

  @Test
  public void testAtomicMove() throws Exception {
    // Null inputs should not throw exception
    Utils.atomicMove(null, Paths.get("dest"));
    Utils.atomicMove(Paths.get("src"), null);

    Path src = Files.createTempFile("tus-atomic-src", ".tmp");
    Path dst = Files.createTempFile("tus-atomic-dst", ".tmp");
    try {
      Files.write(src, "atomic test content".getBytes());
      Utils.atomicMove(src, dst);

      assertThat(Files.exists(src), is(false));
      assertThat(Files.exists(dst), is(true));
      assertThat(new String(Files.readAllBytes(dst)), is("atomic test content"));
    } finally {
      Files.deleteIfExists(src);
      Files.deleteIfExists(dst);
    }
  }

  @Test
  public void testLockFileExclusivelyAndShared() throws Exception {
    Path tempFile = Files.createTempFile("tus-test-lock", ".tmp");
    try (FileChannel channel =
        FileChannel.open(tempFile, StandardOpenOption.WRITE, StandardOpenOption.READ)) {
      FileLock lock1 = Utils.lockFileExclusively(channel);
      assertThat(lock1, is(notNullValue()));
      lock1.release();

      FileLock lock2 = Utils.lockFileShared(channel);
      assertThat(lock2, is(notNullValue()));
      lock2.release();
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void testSleep() {
    long start = System.currentTimeMillis();
    Utils.sleep(50L);
    long end = System.currentTimeMillis();
    assertThat(end - start >= 50L || (end - start + 10) >= 50L, is(true));
  }

  @Test
  public void testParseUploadChecksumHeaderMissing() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn(null);

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderBlank() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("   ");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderInvalidFormat() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderUnsupportedAlgorithm() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("invalid-algo value");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderNoValue() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 ");

    assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderValid() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 value123");

    Utils.ChecksumInfo info = Utils.parseUploadChecksumHeader(request);
    assertThat(info, is(notNullValue()));
    assertThat(info.getAlgorithm(), is(ChecksumAlgorithm.SHA256));
    assertThat(info.getValue(), is("value123"));
  }

  @Test
  public void testParseUploadChecksumHeaderValidBase64AndHexAndCharacters() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    // test typical hex, base64, base64url characters: a-zA-Z0-9+/=-_
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 abcdef0123456789+/=-_");

    Utils.ChecksumInfo info = Utils.parseUploadChecksumHeader(request);
    assertThat(info, is(notNullValue()));
    assertThat(info.getAlgorithm(), is(ChecksumAlgorithm.SHA256));
    assertThat(info.getValue(), is("abcdef0123456789+/=-_"));
  }

  @Test
  public void testParseUploadChecksumHeaderInvalidPathTraversal() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    // Test directory traversal attempt
    when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn("sha256 ../../etc/passwd");

    Utils.ChecksumInfo info = Utils.parseUploadChecksumHeader(request);
    assertThat(info, is(nullValue()));
  }

  @Test
  public void testParseUploadChecksumHeaderInvalidCharacters() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    // Test dots, backslashes, percent signs, brackets, spaces, etc.
    List<String> invalidValues =
        Arrays.asList(
            "sha256 value.123",
            "sha256 value\\123",
            "sha256 value%123",
            "sha256 value[123]",
            "sha256 value 123",
            "sha256 value?123",
            "sha256 value*123",
            "sha256 value:123");

    for (String val : invalidValues) {
      when(request.getHeader(HttpHeader.UPLOAD_CHECKSUM)).thenReturn(val);
      assertThat(Utils.parseUploadChecksumHeader(request), is(nullValue()));
    }
  }

  @Test
  public void testGetUploadUri() {
    TusServletRequest request = mock(TusServletRequest.class);
    TusServletResponse response = mock(TusServletResponse.class);

    when(request.getRequestURI()).thenReturn("/files/123");
    when(response.getHeader(HttpHeader.LOCATION)).thenReturn("/files/location");

    when(request.getMethod()).thenReturn("POST");
    assertThat(Utils.getUploadUri(request, response), is("/files/location"));
    when(request.getMethod()).thenReturn("PUT");
    assertThat(Utils.getUploadUri(request, response), is("/files/location"));
    when(request.getMethod()).thenReturn("PATCH");
    assertThat(Utils.getUploadUri(request, response), is("/files/123"));
    when(request.getMethod()).thenReturn("GET");
    assertThat(Utils.getUploadUri(request, response), is("/files/123"));
    when(request.getMethod()).thenReturn("HEAD");
    assertThat(Utils.getUploadUri(request, response), is("/files/123"));

    when(request.getMethod()).thenReturn("POST");
    assertThat(Utils.getUploadUri(request, null), is(nullValue()));
    when(request.getMethod()).thenReturn("PATCH");
    assertThat(Utils.getUploadUri(null, response), is(nullValue()));
  }

  @Test
  public void testDetectProtocolVersion() {
    HttpServletRequest unversionedRequest = mock(HttpServletRequest.class);
    assertThat(
        Utils.detectProtocolVersion(unversionedRequest, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.TUS_1_0_0));

    HttpServletRequest request = mock(HttpServletRequest.class);

    // AUTO mode with Tus-Resumable header -> TUS_1_0_0
    when(request.getHeader(HttpHeader.TUS_RESUMABLE)).thenReturn("1.0.0");
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.TUS_1_0_0));

    // AUTO mode with Upload-Complete header -> RUFH
    HttpServletRequest rufhReq = mock(HttpServletRequest.class);
    when(rufhReq.getHeader(HttpHeader.UPLOAD_COMPLETE)).thenReturn("?0");
    assertThat(
        Utils.detectProtocolVersion(rufhReq, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.RUFH));

    // Explicit TUS_1_0_0 mode
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.TUS_1_0_0),
        is(me.desair.tus.server.ProtocolVersion.TUS_1_0_0));

    // Explicit RUFH mode
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.RUFH),
        is(me.desair.tus.server.ProtocolVersion.RUFH));

    // Null request
    assertThat(
        Utils.detectProtocolVersion(null, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.TUS_1_0_0));
  }

  @Test
  public void testExtractUriPath() {
    assertThat(Utils.extractUriPath("/api/files"), is("/api/files"));
    assertThat(Utils.extractUriPath("https://upload.example.com/api/files"), is("/api/files"));
    assertThat(Utils.extractUriPath("http://localhost:8080/files/upload"), is("/files/upload"));
    assertThat(Utils.extractUriPath("https://test.example.com/uploads"), is("/uploads"));
    assertThat(Utils.extractUriPath("https://upload.example.com"), is("/"));
    assertThat(Utils.extractUriPath("https://upload.example.com/"), is("/"));
    assertThat(Utils.extractUriPath(null), is("/"));
    assertThat(Utils.extractUriPath(""), is("/"));
  }

  @Test
  public void testExtractUriOrigin() {
    assertThat(
        Utils.extractUriOrigin("https://upload.example.com/api/files"),
        is("https://upload.example.com"));
    assertThat(Utils.extractUriOrigin("http://localhost:8080/files"), is("http://localhost:8080"));
    assertThat(
        Utils.extractUriOrigin("https://upload.example.com"), is("https://upload.example.com"));
    assertThat(Utils.extractUriOrigin("/api/files"), is(""));
    assertThat(Utils.extractUriOrigin(null), is(""));
    assertThat(Utils.extractUriOrigin(""), is(""));
  }

  @Test
  public void testGetUploadUriOnCreation() {
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));

    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn("/api/files");

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/files");

    // With requestURI set and storageService uploadUri set
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService), is("/api/files/test-id"));

    // With null request and storageService set
    assertThat(Utils.getUploadUriOnCreation(info, null, storageService), is("/api/files/test-id"));
  }

  @Test(expected = NullPointerException.class)
  public void testGetUploadUriOnCreationNullUploadInfoThrows() {
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn("/api/files");

    Utils.getUploadUriOnCreation(null, null, storageService);
  }

  @Test(expected = NullPointerException.class)
  public void testGetUploadUriOnCreationNullUploadIdThrows() {
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn("/api/files");

    Utils.getUploadUriOnCreation(
        new me.desair.tus.server.upload.UploadInfo(), null, storageService);
  }

  @Test(expected = NullPointerException.class)
  public void testGetUploadUriOnCreationNullStorageServiceThrows() {
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));

    Utils.getUploadUriOnCreation(info, null, null);
  }

  @Test(expected = NullPointerException.class)
  public void testGetUploadUriOnCreationNullUploadUriThrows() {
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));

    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn(null);

    Utils.getUploadUriOnCreation(info, null, storageService);
  }

  @Test
  public void testGetUploadUriOnCreationAbsoluteUrl() {
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));

    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/api/files");

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/api/files");

    // Absolute URL with request
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService),
        is("https://upload.example.com/api/files/test-id"));

    // Absolute URL with regex path request
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/users/[0-9]+/files");
    when(request.getRequestURI()).thenReturn("/users/42/files");
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService),
        is("https://upload.example.com/users/42/files/test-id"));

    // Absolute URL with regex path request not starting with /
    when(request.getRequestURI()).thenReturn("users/42/files");
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService),
        is("https://upload.example.com/users/42/files/test-id"));

    // Relative URL with regex path request
    when(storageService.getUploadUri()).thenReturn("/users/[0-9]+/files");
    when(request.getRequestURI()).thenReturn("/users/42/files");
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService), is("/users/42/files/test-id"));

    // Absolute URL with null request
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/api/files");
    assertThat(
        Utils.getUploadUriOnCreation(info, null, storageService),
        is("https://upload.example.com/api/files/test-id"));

    // Absolute URL with root path and null request
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com");
    assertThat(
        Utils.getUploadUriOnCreation(info, null, storageService),
        is("https://upload.example.com/test-id"));
  }

  @Test
  public void testIsExistingUploadResource() throws Exception {
    assertThat(Utils.isExistingUploadResource(null, null, "owner"), is(false));

    HttpServletRequest request = mock(HttpServletRequest.class);
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);

    when(request.getRequestURI()).thenReturn("/files");
    when(storageService.getUploadUri()).thenReturn("/files");
    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(false));

    when(request.getRequestURI()).thenReturn("/files/");
    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(false));

    when(request.getRequestURI()).thenReturn("/files/123");
    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(null);
    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(false));

    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(info);
    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(true));
  }

  @Test
  public void testIsCreationEndpoint() throws Exception {
    assertThat(Utils.isCreationEndpoint(null, null), is(false));

    HttpServletRequest request = mock(HttpServletRequest.class);
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);

    when(request.getRequestURI()).thenReturn("/files");
    when(storageService.getUploadUri()).thenReturn("/files");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(true));

    when(request.getRequestURI()).thenReturn("/files/");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(true));

    when(request.getRequestURI()).thenReturn("/files/123");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(false));

    // Test with absolute URL configured
    when(storageService.getUploadUri()).thenReturn("https://upload.example.com/files");
    when(request.getRequestURI()).thenReturn("/files");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(true));

    when(request.getRequestURI()).thenReturn("/files/");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(true));

    when(request.getRequestURI()).thenReturn("/files/123");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(false));
  }

  @Test
  public void testDetermineProtocolVersionBranches() {
    HttpServletRequest request = mock(HttpServletRequest.class);

    // Content-Type: application/offset+octet-stream
    when(request.getHeader(HttpHeader.CONTENT_TYPE)).thenReturn("application/offset+octet-stream");
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.RUFH));

    // Method HEAD without tus headers
    when(request.getHeader(HttpHeader.CONTENT_TYPE)).thenReturn(null);
    when(request.getMethod()).thenReturn("HEAD");
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.RUFH));

    // Method DELETE without tus headers
    when(request.getMethod()).thenReturn("DELETE");
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.RUFH));

    // Method POST with Upload-Offset
    when(request.getMethod()).thenReturn("POST");
    when(request.getHeader(HttpHeader.UPLOAD_OFFSET)).thenReturn("0");
    assertThat(
        Utils.detectProtocolVersion(request, me.desair.tus.server.ProtocolVersion.AUTO),
        is(me.desair.tus.server.ProtocolVersion.RUFH));
  }

  @Test
  public void testIsCreationEndpointEdgeCases() {
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    HttpServletRequest request = mock(HttpServletRequest.class);

    assertThat(Utils.isCreationEndpoint(request, null), is(false));
    assertThat(Utils.isCreationEndpoint(null, storageService), is(false));

    when(request.getRequestURI()).thenReturn(null);
    when(storageService.getUploadUri()).thenReturn("/files");
    assertThat(Utils.isCreationEndpoint(request, storageService), is(false));

    when(request.getRequestURI()).thenReturn("/files");
    when(storageService.getUploadUri()).thenReturn(null);
    assertThat(Utils.isCreationEndpoint(request, storageService), is(false));
  }

  @Test
  public void testIsExistingUploadResourceExpiredAndNull() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);

    when(request.getRequestURI()).thenReturn("/files/123");
    when(storageService.getUploadUri()).thenReturn("/files");

    me.desair.tus.server.upload.UploadInfo expired = new me.desair.tus.server.upload.UploadInfo();
    expired.setExpirationTimestamp(System.currentTimeMillis() - 1000L);
    when(storageService.getUploadInfo("/files/123", "owner")).thenReturn(expired);

    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(false));
    assertThat(Utils.isExistingUploadResource(request, null, "owner"), is(false));
  }

  @Test
  public void testIsExistingUploadResourceNullRequestUri() throws Exception {
    HttpServletRequest request = mock(HttpServletRequest.class);
    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);

    when(request.getRequestURI()).thenReturn(null);
    when(storageService.getUploadUri()).thenReturn("/files");

    assertThat(Utils.isExistingUploadResource(request, storageService, "owner"), is(false));
  }

  @Test
  public void testCreateScheduledDaemonExecutorAndScheduleWatchdog() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean executed =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    java.util.concurrent.ScheduledExecutorService executor =
        Utils.scheduleWatchdog(
            "test-watchdog",
            () -> executed.set(true),
            10,
            10,
            java.util.concurrent.TimeUnit.MILLISECONDS);

    assertThat(executor, is(notNullValue()));
    assertThat(executor.isShutdown(), is(false));

    Thread.sleep(50);
    assertThat(executed.get(), is(true));

    Utils.shutdownExecutor(executor);
    assertThat(executor.isShutdown(), is(true));
  }

  @Test
  public void testShutdownExecutorNullOrShutdown() {
    Utils.shutdownExecutor(null);

    java.util.concurrent.ScheduledExecutorService executor =
        Utils.createScheduledDaemonExecutor("test-shutdown");
    Utils.shutdownExecutor(executor);
    assertThat(executor.isShutdown(), is(true));

    Utils.shutdownExecutor(executor);
    assertThat(executor.isShutdown(), is(true));
  }

  @Test
  public void testScheduleWatchdogWithZeroPeriodOrNullTask() {
    java.util.concurrent.ScheduledExecutorService executor1 =
        Utils.scheduleWatchdog(
            "test-zero-period", () -> {}, 0, 0, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(executor1, is(notNullValue()));
    Utils.shutdownExecutor(executor1);

    java.util.concurrent.ScheduledExecutorService executor2 =
        Utils.scheduleWatchdog(
            "test-null-task", null, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
    assertThat(executor2, is(notNullValue()));
    Utils.shutdownExecutor(executor2);
  }

  @Test
  public void testShutdownExecutorWithException() {
    java.util.concurrent.ScheduledExecutorService mockExecutor =
        mock(java.util.concurrent.ScheduledExecutorService.class);
    when(mockExecutor.isShutdown()).thenReturn(false);
    when(mockExecutor.shutdownNow()).thenThrow(new RuntimeException("Shutdown error"));

    Utils.shutdownExecutor(mockExecutor);
  }

  @Test
  public void testInterruptStreamNull() {
    Utils.interruptStream(null);
  }

  @Test
  public void testInterruptStreamStandardInputStream() throws Exception {
    java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(new byte[0]);
    Utils.interruptStream(bis);
  }

  @Test
  public void testInterruptStreamInterruptibleInputStream() {
    InterruptibleInputStream iis =
        new InterruptibleInputStream(new java.io.ByteArrayInputStream(new byte[0]));
    Utils.interruptStream(iis);
    assertThat(iis.isInterrupted(), is(true));
  }

  @Test
  public void testInterruptStreamWithException() {
    InterruptibleInputStream faultyStream =
        new InterruptibleInputStream(new java.io.ByteArrayInputStream(new byte[0])) {
          @Override
          public void interrupt() {
            throw new RuntimeException("Error during interrupt");
          }
        };
    Utils.interruptStream(faultyStream);
  }

  @Test
  public void testInterruptThreadNull() {
    // Should do nothing without exception
    Utils.interruptThread(null);
  }

  @Test
  public void testInterruptThreadAlive() throws Exception {
    java.util.concurrent.atomic.AtomicBoolean wasInterrupted =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    Thread thread =
        new Thread(
            () -> {
              try {
                Thread.sleep(5000L);
              } catch (InterruptedException e) {
                wasInterrupted.set(true);
              }
            },
            "test-interruptible-thread");
    thread.start();

    // Give the thread a moment to start
    Thread.sleep(50L);

    Utils.interruptThread(thread);
    thread.join(2000L);

    assertThat(wasInterrupted.get(), is(true));
  }

  @Test
  public void testInterruptThreadDead() {
    Thread unstartedThread = new Thread(() -> {}, "test-unstarted");
    // Should handle cleanly without exception
    Utils.interruptThread(unstartedThread);
  }

  @Test
  public void ensureDirectoryExistsWithNullPathShouldDoNothing() throws Exception {
    Utils.ensureDirectoryExists(null);
  }

  @Test
  public void ensureDirectoryExistsWithNewDirectoryShouldCreateDirs() throws Exception {
    Path nestedDir = storagePath.resolve("nested").resolve("sub").resolve("dir");
    assertThat(Files.exists(nestedDir), is(false));

    Utils.ensureDirectoryExists(nestedDir);
    assertThat(Files.exists(nestedDir), is(true));
    assertThat(Files.isDirectory(nestedDir), is(true));
  }

  @Test
  public void ensureDirectoryExistsWithExistingDirectoryShouldBeNoOp() throws Exception {
    Path existingDir = storagePath.resolve("existing-dir");
    Files.createDirectories(existingDir);
    assertThat(Files.exists(existingDir), is(true));

    Utils.ensureDirectoryExists(existingDir);
    assertThat(Files.exists(existingDir), is(true));
  }

  @Test(expected = IOException.class)
  public void ensureDirectoryExistsOnFileConflictShouldThrowIOException() throws Exception {
    Path filePath = storagePath.resolve("file-conflict");
    Files.write(filePath, "content".getBytes());

    // Attempting to create directory where a file already exists should fail
    Path conflictChildDir = filePath.resolve("child");
    Utils.ensureDirectoryExists(conflictChildDir);
  }

  @Test
  public void cleanupTempFilesWithNullOrNonExistentDirShouldDoNothing() {
    Utils.cleanupTempFiles(null, "*.tmp", 1000L);
    Utils.cleanupTempFiles(storagePath.resolve("non-existent-dir"), "*.tmp", 1000L);
    Utils.cleanupTempFiles(storagePath, null, 1000L);
  }

  @Test
  public void cleanupTempFilesShouldDeleteStaleFilesAndRetainFreshFiles() throws Exception {
    Path tempDir = storagePath.resolve("temp-cleanup-test");
    Utils.ensureDirectoryExists(tempDir);

    Path staleFile = tempDir.resolve("tus-azure-chunk-stale.tmp");
    Path freshFile = tempDir.resolve("tus-azure-chunk-fresh.tmp");
    Path unrelatedFile = tempDir.resolve("other-file.txt");

    Files.write(staleFile, "stale".getBytes());
    Files.write(freshFile, "fresh".getBytes());
    Files.write(unrelatedFile, "unrelated".getBytes());

    // Set staleFile modification time to 1 hour ago
    long oneHourAgo = System.currentTimeMillis() - 3600_000L;
    Files.setLastModifiedTime(staleFile, java.nio.file.attribute.FileTime.fromMillis(oneHourAgo));

    // Run cleanup for files older than 30 minutes
    Utils.cleanupTempFiles(tempDir, "tus-azure-chunk-*.tmp", 1800_000L);

    assertThat(Files.exists(staleFile), is(false));
    assertThat(Files.exists(freshFile), is(true));
    assertThat(Files.exists(unrelatedFile), is(true));
  }

  @Test
  public void testIsMediaType() {
    assertThat(Utils.isMediaType(null, "application/offset+octet-stream"), is(false));
    assertThat(Utils.isMediaType("application/offset+octet-stream", null), is(false));
    assertThat(
        Utils.isMediaType("application/offset+octet-stream", "application/offset+octet-stream"),
        is(true));
    assertThat(
        Utils.isMediaType(
            "application/offset+octet-stream;charset=UTF-8", "application/offset+octet-stream"),
        is(true));
    assertThat(
        Utils.isMediaType(
            "application/offset+octet-stream ; charset=utf-8", "application/offset+octet-stream"),
        is(true));
    assertThat(
        Utils.isMediaType("APPLICATION/OFFSET+OCTET-STREAM", "application/offset+octet-stream"),
        is(true));
    assertThat(
        Utils.isMediaType(
            "application/partial-upload; charset=UTF-8", "application/partial-upload"),
        is(true));
    assertThat(Utils.isMediaType("application/json", "application/offset+octet-stream"), is(false));
    assertThat(Utils.isMediaType("text/plain", "application/offset+octet-stream"), is(false));
  }

  @Test
  public void testExtractMediaType() {
    assertThat(Utils.extractMediaType(null), is(nullValue()));
    assertThat(
        Utils.extractMediaType("application/offset+octet-stream"),
        is("application/offset+octet-stream"));
    assertThat(
        Utils.extractMediaType("application/offset+octet-stream;charset=UTF-8"),
        is("application/offset+octet-stream"));
    assertThat(
        Utils.extractMediaType("  application/offset+octet-stream ; charset=utf-8 "),
        is("application/offset+octet-stream"));
  }

  /** Simple serializable class for testing. */
  public static class TestSerializable implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String value;

    public TestSerializable(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }
}
