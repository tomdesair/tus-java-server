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
    InputStream bis =
        new InputStream() {
          @Override
          public int read() throws IOException {
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

  @Test
  public void testNullConstructorArg() {
    try {
      new InterruptibleInputStream(null);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("Delegate InputStream cannot be null", e.getMessage());
    }
  }

  @Test
  public void testDelegateMethods() throws IOException {
    byte[] data = new byte[] {1, 2, 3, 4, 5};
    InputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    assertTrue(iis.markSupported());
    iis.mark(10);
    assertEquals(5, iis.available());
    assertEquals(2, iis.skip(2));
    assertEquals(3, iis.read());
    iis.reset();
    assertEquals(1, iis.read());

    // Test reset after interrupt
    iis.interrupt();
    try {
      iis.reset();
      fail("Expected IOException on reset after interrupt");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("interrupted"));
    }

    try {
      iis.available();
      fail("Expected IOException on available after interrupt");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("interrupted"));
    }

    try {
      iis.skip(1);
      fail("Expected IOException on skip after interrupt");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("interrupted"));
    }
  }

  @Test
  public void testReadExceptionPropagation() throws IOException {
    InputStream exceptionStream =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read error");
          }

          @Override
          public int read(byte[] b) throws IOException {
            throw new IOException("read array error");
          }

          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            throw new IOException("read range error");
          }
        };

    InterruptibleInputStream iis = new InterruptibleInputStream(exceptionStream);

    try {
      iis.read();
      fail("Expected IOException");
    } catch (IOException e) {
      assertEquals("read error", e.getMessage());
    }

    try {
      iis.read(new byte[1]);
      fail("Expected IOException");
    } catch (IOException e) {
      assertEquals("read array error", e.getMessage());
    }

    try {
      iis.read(new byte[1], 0, 1);
      fail("Expected IOException");
    } catch (IOException e) {
      assertEquals("read range error", e.getMessage());
    }
  }
}
