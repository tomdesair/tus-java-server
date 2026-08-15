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
  }

  @Test
  public void testReadSerializableNullPath() throws Exception {
    assertThat(Utils.readSerializable(null, String.class), is(nullValue()));
  }

  @Test
  public void testWriteSerializableNullPath() throws Exception {
    // Should do nothing without exception
    Utils.writeSerializable("test", null);
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
  public void testGetUploadUriOnCreation() {
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));

    me.desair.tus.server.upload.UploadStorageService storageService =
        mock(me.desair.tus.server.upload.UploadStorageService.class);
    when(storageService.getUploadUri()).thenReturn("/api/files");

    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getRequestURI()).thenReturn("/files");

    // With requestURI set and storageService uploadUri set
    assertThat(
        Utils.getUploadUriOnCreation(info, request, storageService), is("/api/files/test-id"));

    // With null request and storageService set
    assertThat(Utils.getUploadUriOnCreation(info, null, storageService), is("/api/files/test-id"));

    // With null request and null storageService
    assertThat(Utils.getUploadUriOnCreation(info, null, null), is("/test-id"));

    // With null uploadInfo
    assertThat(Utils.getUploadUriOnCreation(null, null, null), is("/"));

    // With uploadInfo having null id
    assertThat(
        Utils.getUploadUriOnCreation(new me.desair.tus.server.upload.UploadInfo(), null, null),
        is("/"));
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
