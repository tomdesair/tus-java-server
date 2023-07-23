package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;

/** Test cases for the HttpChunkedEncodingInputStream class. */
public class HttpChunkedEncodingInputStreamTest {

  Map<String, List<String>> trailerHeaders;

  @Before
  public void setUp() {
    trailerHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  }

  @Test
  public void chunkedWithoutHeaders() throws IOException {
    String content =
        "4\r\n"
            + "Wiki\r\n"
            + "5\r\n"
            + "pedia\r\n"
            + "D\r\n"
            + " in\n"
            + "\n\r"
            + "chunks.\r\n"
            + "0\r\n"
            + "\r\n";

    HttpChunkedEncodingInputStream inputStream =
        new HttpChunkedEncodingInputStream(IOUtils.toInputStream(content, StandardCharsets.UTF_8));

    String expectedContent = "Wikipedia in\n" + "\n\r" + "chunks.";

    StringWriter writer = new StringWriter();
    IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
    inputStream.close();

    assertEquals(expectedContent, writer.toString());
  }

  @Test
  public void chunkedWithHeaders() throws IOException {
    String content =
        "8\r\n"
            + "Mozilla \r\n"
            + "A\r\n"
            + "Developer \r\n"
            + "7\r\n"
            + "Network\r\n"
            + "0\r\n"
            + "Expires: Wed, 21 Oct 2015 07:28:00 GMT\r\n"
            + "\r\n";

    HttpChunkedEncodingInputStream inputStream =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(content, StandardCharsets.UTF_8), trailerHeaders);

    String expectedContent = "Mozilla Developer Network";

    StringWriter writer = new StringWriter();
    IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
    inputStream.close();

    assertEquals(expectedContent, writer.toString());

    assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", trailerHeaders.get("expires").get(0));
  }

  @Test
  public void chunkedWithFoldedHeaders() throws IOException {
    String content =
        "8\r\n"
            + "Mozilla \r\n"
            + "A\r\n"
            + "Developer \r\n"
            + "7\r\n"
            + "Network\r\n"
            + "0\r\n"
            + "Expires: Wed, 21 Oct 2015\n"
            + " 07:28:00 GMT\r\n"
            + "Cookie: ABC\n"
            + "\tDEF\r\n"
            + "\r\n";

    HttpChunkedEncodingInputStream inputStream =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(content, StandardCharsets.UTF_8), trailerHeaders);

    String expectedContent = "Mozilla Developer Network";

    StringWriter writer = new StringWriter();
    IOUtils.copy(inputStream, writer, StandardCharsets.UTF_8);
    inputStream.close();

    assertEquals(expectedContent, writer.toString());

    assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", trailerHeaders.get("expires").get(0));
    assertEquals("ABC DEF", trailerHeaders.get("cookie").get(0));
  }

  @Test
  public void testChunkedInputStream() throws IOException {
    String correctInput =
        "10;key=\"value\r\n"
            + "newline\"\r\n"
            + "1234567890"
            + "123456\r\n"
            + "5\r\n"
            + "12345\r\n"
            + "0\r\n"
            + "Footer1: abcde\r\n"
            + "Footer2: fghij\r\n";

    String correctResult = "123456789012345612345";

    // Test for when buffer is larger than chunk size
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(correctInput, StandardCharsets.UTF_8), trailerHeaders);
    StringWriter writer = new StringWriter();
    IOUtils.copy(in, writer, StandardCharsets.UTF_8);
    in.close();

    assertEquals(correctResult, writer.toString());

    assertEquals("abcde", trailerHeaders.get("footer1").get(0));
    assertEquals("fghij", trailerHeaders.get("footer2").get(0));
  }

  @Test(expected = IOException.class)
  public void testCorruptChunkedInputStream1() throws IOException {
    // missing \r\n at the end of the first chunk
    String corruptInput =
        "10;key=\"val\\ue\"\r\n"
            + "1234567890"
            + "12345\r\n"
            + "5\r\n"
            + "12345\r\n"
            + "0\r\n"
            + "Footer1: abcde\r\n"
            + "Footer2: fghij\r\n";

    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(corruptInput, StandardCharsets.UTF_8), trailerHeaders);
    StringWriter writer = new StringWriter();
    IOUtils.copy(in, writer, StandardCharsets.UTF_8);
  }

  @Test
  public void testEmptyChunkedInputStream() throws IOException {
    String input = "0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);
    StringWriter writer = new StringWriter();
    IOUtils.copy(in, writer, StandardCharsets.UTF_8);
    assertEquals(0, writer.toString().length());
  }

  @Test
  public void testReadPartialByteArray() throws IOException {
    String input = "A\r\n0123456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    byte[] byteArray = new byte[5];
    in.read(byteArray);
    in.close();

    assertEquals("01234", new String(byteArray));
  }

  @Test
  public void testReadByte() throws IOException {
    String input = "4\r\n" + "0123\r\n" + "6\r\n" + "456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    assertEquals('0', (char) in.read());
    assertEquals('1', (char) in.read());
    assertEquals('2', (char) in.read());
    assertEquals('3', (char) in.read());
    assertEquals('4', (char) in.read());
    assertEquals('5', (char) in.read());
    assertEquals('6', (char) in.read());
    assertEquals('7', (char) in.read());
    assertEquals('8', (char) in.read());
    assertEquals('9', (char) in.read());
    in.close();
  }

  @Test
  public void testReadEof() throws IOException {
    String input = "A\r\n0123456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    byte[] byteArray = new byte[10];
    in.read(byteArray);

    assertEquals(-1, in.read());
    assertEquals(-1, in.read());
  }

  @Test
  public void testReadEof2() throws IOException {
    String input = "A\r\n0123456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    byte[] byteArray = new byte[10];
    in.read(byteArray);

    assertEquals(-1, in.read(byteArray));
    assertEquals(-1, in.read(byteArray));
  }

  @Test
  public void testReadClosed() throws IOException {
    String input = "A\r\n0123456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    in.close();

    try {
      byte[] byteArray = new byte[10];
      assertEquals(-1, in.read(byteArray));
      fail();
    } catch (Exception ex) {
      assertTrue(ex instanceof IOException);
    }

    try {
      assertEquals(-1, in.read());
      fail();
    } catch (Exception ex) {
      assertTrue(ex instanceof IOException);
    }

    // double close has not effect
    in.close();
  }

  @Test(expected = IllegalArgumentException.class)
  public void testNullInputstream() throws IOException {
    InputStream in = null;
    try {
      in = new HttpChunkedEncodingInputStream(null);
    } finally {
      if (in != null) {
        in.close();
      }
    }
  }

  @Test(expected = IOException.class)
  public void testNegativeChunkSize() throws IOException {
    String input = "-A\r\n0123456789\r\n0\r\n";
    InputStream in =
        new HttpChunkedEncodingInputStream(
            IOUtils.toInputStream(input, StandardCharsets.UTF_8), trailerHeaders);

    byte[] byteArray = new byte[10];
    in.read(byteArray);
  }
}
