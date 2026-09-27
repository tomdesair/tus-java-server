package me.desair.tus.server.exception;

import static org.junit.Assert.assertEquals;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.Test;

public class UploadDigestMismatchExceptionTest {

  @Test
  public void testExceptionStatusAndMessage() {
    UploadDigestMismatchException exception =
        new UploadDigestMismatchException("Calculated digest does not match client digest");

    assertEquals(HttpServletResponse.SC_BAD_REQUEST, exception.getStatus());
    assertEquals("Calculated digest does not match client digest", exception.getMessage());
  }
}
