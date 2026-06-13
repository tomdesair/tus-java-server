package me.desair.tus.server.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * An InputStream wrapper that can be interrupted by another thread. When interrupted, it throws an
 * IOException on subsequent or blocking read operations.
 */
public class InterruptibleInputStream extends InputStream {

  private final InputStream delegate;
  private volatile boolean interrupted = false;

  public InterruptibleInputStream(InputStream delegate) {
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate InputStream cannot be null");
    }
    this.delegate = delegate;
  }

  private void checkInterrupted() throws IOException {
    if (interrupted) {
      throw new IOException("Stream was interrupted by the upload locking service watchdog");
    }
  }

  public void interrupt() {
    interrupted = true;
    try {
      delegate.close();
    } catch (IOException e) {
      // Ignore close exception during interrupt
    }
  }

  public boolean isInterrupted() {
    return interrupted;
  }

  @Override
  public int read() throws IOException {
    checkInterrupted();
    try {
      int result = delegate.read();
      checkInterrupted();
      return result;
    } catch (IOException e) {
      checkInterrupted();
      throw e;
    }
  }

  @Override
  public int read(byte[] b) throws IOException {
    checkInterrupted();
    try {
      int result = delegate.read(b);
      checkInterrupted();
      return result;
    } catch (IOException e) {
      checkInterrupted();
      throw e;
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    checkInterrupted();
    try {
      int result = delegate.read(b, off, len);
      checkInterrupted();
      return result;
    } catch (IOException e) {
      checkInterrupted();
      throw e;
    }
  }

  @Override
  public long skip(long n) throws IOException {
    checkInterrupted();
    return delegate.skip(n);
  }

  @Override
  public int available() throws IOException {
    checkInterrupted();
    return delegate.available();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  @Override
  public synchronized void mark(int readlimit) {
    delegate.mark(readlimit);
  }

  @Override
  public synchronized void reset() throws IOException {
    checkInterrupted();
    delegate.reset();
  }

  @Override
  public boolean markSupported() {
    return delegate.markSupported();
  }
}
