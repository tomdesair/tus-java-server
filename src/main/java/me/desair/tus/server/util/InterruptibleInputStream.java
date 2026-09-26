package me.desair.tus.server.util;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.upload.UploadLock;

/**
 * An InputStream wrapper that can be interrupted by another thread. When interrupted, it throws an
 * IOException on subsequent or blocking read operations.
 */
public class InterruptibleInputStream extends InputStream {

  private final InputStream delegate;
  private UploadLock uploadLock;
  private volatile boolean interrupted = false;

  /**
   * Constructs an interruptible input stream wrapping the given delegate stream and associating it
   * with the provided {@link UploadLock}.
   *
   * @param delegate The delegate input stream to wrap
   * @param uploadLock The upload lock associated with this stream, or null
   * @throws IllegalArgumentException if the delegate is null
   */
  public InterruptibleInputStream(InputStream delegate, UploadLock uploadLock) {
    if (delegate == null) {
      throw new IllegalArgumentException("Delegate InputStream cannot be null");
    }
    this.delegate = delegate;
    this.uploadLock = uploadLock;
  }

  /**
   * Constructs an interruptible input stream wrapping the given delegate stream.
   *
   * @param delegate The delegate input stream to wrap
   * @throws IllegalArgumentException if the delegate is null
   */
  public InterruptibleInputStream(InputStream delegate) {
    this(delegate, null);
  }

  /**
   * Sets the corresponding {@link UploadLock} associated with this stream.
   *
   * @param uploadLock The upload lock
   */
  public void setCorrespondingUploadLock(UploadLock uploadLock) {
    this.uploadLock = uploadLock;
  }

  /**
   * Retrieves the corresponding {@link UploadLock} associated with this stream.
   *
   * @return The upload lock, or null if none is set
   */
  public UploadLock getCorrespondingUploadLock() {
    return uploadLock;
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
    if (uploadLock != null) {
      uploadLock.release();
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
