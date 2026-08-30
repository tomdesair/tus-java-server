package me.desair.tus.server.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.commons.io.IOUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TusServletRequestTest {

  @Mock private HttpServletRequest servletRequest;

  private TusServletRequest tusServletRequest;

  @Before
  public void setUp() {
    tusServletRequest = new TusServletRequest(servletRequest, true);
  }

  @Test
  public void getHeaderFromSuper() {
    when(servletRequest.getHeader("X-My-Header")).thenReturn("my-value");

    assertEquals("my-value", tusServletRequest.getHeader("X-My-Header"));
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
    InputStream contentInputStream = tusServletRequest.getContentInputStream();
    IOUtils.toByteArray(contentInputStream);

    // Verify trailer header is returned
    assertEquals("trailer-value", tusServletRequest.getHeader("X-My-Trailer"));
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
    InputStream contentInputStream = tusServletRequest.getContentInputStream();
    IOUtils.toByteArray(contentInputStream);

    // Verify trailer header is returned because super returned a blank string
    assertEquals("trailer-value", tusServletRequest.getHeader("X-My-Trailer"));
  }

  @Test
  public void getHeaderNotFound() {
    when(servletRequest.getHeader("X-My-Header")).thenReturn(null);
    assertNull(tusServletRequest.getHeader("X-My-Header"));
  }
}
