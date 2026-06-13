package me.desair.tus.server.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.junit.Test;

public class UtilsTest {

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
}
