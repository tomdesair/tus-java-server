package me.desair.tus.server.util;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.TusExtension;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TusServletRequestTest {

  @Mock private HttpServletRequest servletRequest;

  private TusServletRequest request;

  @Before
  public void setUp() {
    request = new TusServletRequest(servletRequest, true);
  }

  @Test
  public void testGetContentInputStream() throws Exception {
    byte[] data = "test data".getBytes();
    when(servletRequest.getInputStream())
        .thenReturn(new MockServletInputStream(new ByteArrayInputStream(data)));

    InputStream is = request.getContentInputStream();

    assertThat(is, notNullValue());

    // Read to the end to trigger counting
    byte[] buffer = new byte[1024];
    int bytesRead = is.read(buffer);

    assertThat(bytesRead, is(9));
    assertThat(request.getBytesRead(), is(9L));
  }

  @Test
  public void testGetContentInputStreamChunked() throws Exception {
    TusServletRequest chunkedRequest = new TusServletRequest(servletRequest, true);

    byte[] data = "5\r\ntest \r\n4\r\ndata\r\n0\r\n\r\n".getBytes();
    when(servletRequest.getInputStream())
        .thenReturn(new MockServletInputStream(new ByteArrayInputStream(data)));
    when(servletRequest.getHeader(HttpHeader.TRANSFER_ENCODING)).thenReturn("chunked");

    InputStream is = chunkedRequest.getContentInputStream();

    assertThat(is, notNullValue());

    // Read to the end to trigger counting
    byte[] buffer = new byte[1024];
    int bytesRead = 0;
    int read;
    while ((read = is.read(buffer)) != -1) {
      bytesRead += read;
    }

    assertThat(bytesRead, is(9));
    assertThat(chunkedRequest.getBytesRead(), is(9L));
  }

  @Test
  public void testGetContentInputStreamWithChecksum() throws Exception {
    byte[] data = "test data".getBytes();
    when(servletRequest.getInputStream())
        .thenReturn(new MockServletInputStream(new ByteArrayInputStream(data)));
    when(servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM))
        .thenReturn("sha1 9I3YU4IIYIFsddVND1hNyGMyenw=");

    InputStream is = request.getContentInputStream();
    assertThat(is, notNullValue());

    byte[] buffer = new byte[1024];
    int read;
    while ((read = is.read(buffer)) != -1) {
      // Consume stream completely to calculate checksum
    }

    assertThat(request.hasCalculatedChecksum(), is(true));
    Set<ChecksumAlgorithm> algorithms = request.getEnabledChecksums();
    assertThat(algorithms, hasItems(ChecksumAlgorithm.SHA1));

    assertThat(
        request.getCalculatedChecksum(ChecksumAlgorithm.SHA1), is("9I3YU4IIYIFsddVND1hNyGMyenw="));
  }

  @Test
  public void testGetContentInputStreamChunkedWithChecksum() throws Exception {
    TusServletRequest chunkedRequest = new TusServletRequest(servletRequest, true);

    byte[] data = "5\r\ntest \r\n4\r\ndata\r\n0\r\n\r\n".getBytes();
    when(servletRequest.getInputStream())
        .thenReturn(new MockServletInputStream(new ByteArrayInputStream(data)));
    when(servletRequest.getHeader(HttpHeader.TRANSFER_ENCODING)).thenReturn("chunked");

    InputStream is = chunkedRequest.getContentInputStream();
    assertThat(is, notNullValue());

    byte[] buffer = new byte[1024];
    int read;
    while ((read = is.read(buffer)) != -1) {
      // Consume stream completely to calculate checksum
    }

    assertThat(chunkedRequest.hasCalculatedChecksum(), is(true));
    Set<ChecksumAlgorithm> algorithms = chunkedRequest.getEnabledChecksums();
    // Since it's chunked and checksum can come at the end, it should keep track of all algorithms
    assertThat(algorithms, hasItems(ChecksumAlgorithm.values()));

    assertThat(
        chunkedRequest.getCalculatedChecksum(ChecksumAlgorithm.SHA1),
        is("9I3YU4IIYIFsddVND1hNyGMyenw="));
  }

  @Test
  public void testIsProcessedBy() {
    TusExtension extension = mock(TusExtension.class);
    when(extension.getName()).thenReturn("test");

    assertThat(request.isProcessedBy(extension), is(false));

    request.addProcessor(extension);

    assertThat(request.isProcessedBy(extension), is(true));
  }

  @Test
  public void testGetHeader() {
    when(servletRequest.getHeader("X-Custom-Header")).thenReturn("custom-value");

    assertThat(request.getHeader("X-Custom-Header"), is("custom-value"));
    assertThat(request.getHeader("X-Non-Existent"), is(nullValue()));
  }

  @Test
  public void getHeaderFromSuper() {
    when(servletRequest.getHeader("X-My-Header")).thenReturn("my-value");

    assertEquals("my-value", request.getHeader("X-My-Header"));
  }

  @Test
  public void getHeaderFromTrailer() throws Exception {
    when(servletRequest.getHeader("Transfer-Encoding")).thenReturn("chunked");
    when(servletRequest.getHeader("X-My-Trailer")).thenReturn(null);

    String chunkedContent = "5\r\n" + "hello\r\n" + "0\r\n" + "X-My-Trailer: trailer-value\r\n\r\n";
    InputStream bais = new ByteArrayInputStream(chunkedContent.getBytes(StandardCharsets.UTF_8));

    when(servletRequest.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              @Override
              public boolean isFinished() {
                return false;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener readListener) {}

              @Override
              public int read() throws IOException {
                return bais.read();
              }
            });

    // Read the whole input stream to parse trailers
    InputStream contentInputStream = request.getContentInputStream();
    IOUtils.toByteArray(contentInputStream);

    // Verify trailer header is returned
    assertEquals("trailer-value", request.getHeader("X-My-Trailer"));
  }

  @Test
  public void getHeaderBlankFallsBackToTrailer() throws Exception {
    when(servletRequest.getHeader("Transfer-Encoding")).thenReturn("chunked");
    when(servletRequest.getHeader("X-My-Trailer")).thenReturn("");

    String chunkedContent = "5\r\n" + "hello\r\n" + "0\r\n" + "X-My-Trailer: trailer-value\r\n\r\n";
    InputStream bais = new ByteArrayInputStream(chunkedContent.getBytes(StandardCharsets.UTF_8));

    when(servletRequest.getInputStream())
        .thenReturn(
            new ServletInputStream() {
              @Override
              public boolean isFinished() {
                return false;
              }

              @Override
              public boolean isReady() {
                return true;
              }

              @Override
              public void setReadListener(ReadListener readListener) {}

              @Override
              public int read() throws IOException {
                return bais.read();
              }
            });

    // Read the whole input stream to parse trailers
    InputStream contentInputStream = request.getContentInputStream();
    IOUtils.toByteArray(contentInputStream);

    // Verify trailer header is returned because super returned a blank string
    assertEquals("trailer-value", request.getHeader("X-My-Trailer"));
  }

  @Test
  public void getHeaderNotFound() {
    when(servletRequest.getHeader("X-My-Header")).thenReturn(null);
    assertNull(request.getHeader("X-My-Header"));
  }
}
