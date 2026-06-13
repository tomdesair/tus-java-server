package me.desair.tus.server.util;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

public class InterruptibleInputStreamTest {

  @Test
  public void testNormalRead() throws IOException {
    byte[] data = new byte[] {1, 2, 3, 4, 5};
    InputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    assertEquals(1, iis.read());
    byte[] buf = new byte[2];
    assertEquals(2, iis.read(buf));
    assertArrayEquals(new byte[] {2, 3}, buf);
    assertEquals(2, iis.read(buf, 0, 2));
    assertArrayEquals(new byte[] {4, 5}, buf);
    assertEquals(-1, iis.read());
    iis.close();
  }

  @Test
  public void testInterruptBeforeRead() throws IOException {
    byte[] data = new byte[] {1, 2, 3};
    InputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    assertFalse(iis.isInterrupted());
    iis.interrupt();
    assertTrue(iis.isInterrupted());

    try {
      iis.read();
      fail("Expected IOException on read after interrupt");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("interrupted"));
    }
  }

  @Test
  public void testInterruptDuringRead() throws IOException {
    // Custom stream that blocks or behaves as expected
    InputStream bis =
        new InputStream() {
          @Override
          public int read() throws IOException {
            // Return a byte normally
            return 42;
          }

          @Override
          public void close() throws IOException {
            throw new IOException("closed");
          }
        };

    InterruptibleInputStream iis = new InterruptibleInputStream(bis);
    assertEquals(42, iis.read());

    iis.interrupt();
    assertTrue(iis.isInterrupted());
    try {
      iis.read();
      fail("Expected IOException on read after interrupt");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("interrupted"));
    }
  }
}
