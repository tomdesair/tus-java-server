package me.desair.tus.server.upload;

import java.io.Closeable;
import java.io.IOException;

/**
 * Abstract base class for services that manage closeable resources and optional JVM shutdown hooks.
 */
public abstract class AbstractCloseableResourceService implements Closeable {

  private final Thread shutdownHook;
  private volatile boolean closed = false;

  /** Constructs a closeable resource service without a JVM shutdown hook. */
  protected AbstractCloseableResourceService() {
    this(null);
  }

  /**
   * Constructs a closeable resource service and optionally registers a JVM shutdown hook.
   *
   * @param shutdownHookName The thread name for the shutdown hook, or null if no hook is needed
   */
  protected AbstractCloseableResourceService(String shutdownHookName) {
    if (shutdownHookName != null) {
      this.shutdownHook = new Thread(this::closeQuietly, shutdownHookName);
      registerShutdownHook();
    } else {
      this.shutdownHook = null;
    }
  }

  /**
   * Checks whether this service has been closed.
   *
   * @return true if the service is closed, false otherwise
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Closes the service and deregisters the JVM shutdown hook if registered. Subclasses should
   * implement {@link #cleanupOnClose()} to execute their specific resource cleanup.
   *
   * @throws IOException If closing fails
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      deregisterShutdownHook();
      cleanupOnClose();
    }
  }

  /**
   * Performs subclass-specific resource cleanup and shutdown logic when the service is closed.
   *
   * @throws IOException If closing fails
   */
  protected abstract void cleanupOnClose() throws IOException;

  /** Safely registers the shutdown hook with the JVM runtime. */
  protected void registerShutdownHook() {
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().addShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down
      }
    }
  }

  /** Safely deregisters the shutdown hook from the JVM runtime. */
  protected void deregisterShutdownHook() {
    if (shutdownHook != null) {
      try {
        Runtime.getRuntime().removeShutdownHook(shutdownHook);
      } catch (IllegalStateException ignored) {
        // JVM is already shutting down
      }
    }
  }

  private void closeQuietly() {
    try {
      close();
    } catch (Exception ignored) {
      // Ignored during shutdown
    }
  }
}
