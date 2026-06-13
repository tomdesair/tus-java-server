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
