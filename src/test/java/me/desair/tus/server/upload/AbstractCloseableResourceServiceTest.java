package me.desair.tus.server.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AbstractCloseableResourceServiceTest {

  private static class TestResourceService extends AbstractCloseableResourceService {
    private final AtomicInteger cleanupCount = new AtomicInteger(0);
    private boolean throwOnCleanup = false;

    public TestResourceService() {
      super();
    }

    public TestResourceService(String shutdownHookName) {
      super(shutdownHookName);
    }

    @Override
    protected void cleanupOnClose() throws IOException {
      cleanupCount.incrementAndGet();
      if (throwOnCleanup) {
        throw new IOException("Cleanup failed");
      }
    }

    public int getCleanupCount() {
      return cleanupCount.get();
    }

    public void setThrowOnCleanup(boolean throwOnCleanup) {
      this.throwOnCleanup = throwOnCleanup;
    }
  }

  @Test
  public void testDefaultConstructorAndIdempotentClose() throws Exception {
    TestResourceService service = new TestResourceService();
    assertFalse(service.isClosed());
    assertEquals(0, service.getCleanupCount());

    service.close();
    assertTrue(service.isClosed());
    assertEquals(1, service.getCleanupCount());

    // Subsequent close() should be an idempotent no-op
    service.close();
    assertTrue(service.isClosed());
    assertEquals(1, service.getCleanupCount());
  }

  @Test
  public void testConstructorWithShutdownHookName() throws Exception {
    TestResourceService service = new TestResourceService("test-shutdown-hook");
    assertFalse(service.isClosed());

    service.close();
    assertTrue(service.isClosed());
    assertEquals(1, service.getCleanupCount());
  }

  @Test
  public void testClosePropagatesCleanupExceptionAndMarksClosed() {
    TestResourceService service = new TestResourceService();
    service.setThrowOnCleanup(true);

    assertThrows(IOException.class, service::close);
    assertTrue(service.isClosed());
    assertEquals(1, service.getCleanupCount());

    // Subsequent call does not re-throw because it is already marked closed
    try {
      service.close();
    } catch (IOException e) {
      org.junit.Assert.fail("Subsequent close() on already closed service should not throw");
    }
    assertEquals(1, service.getCleanupCount());
  }

  @Test
  public void testCloseQuietlySwallowsExceptions() throws Exception {
    TestResourceService service = new TestResourceService();
    service.setThrowOnCleanup(true);

    Method closeQuietlyMethod =
        AbstractCloseableResourceService.class.getDeclaredMethod("closeQuietly");
    closeQuietlyMethod.setAccessible(true);

    // Should execute cleanly without throwing
    closeQuietlyMethod.invoke(service);
    assertTrue(service.isClosed());
  }

  @Test
  public void testShutdownHookMethodsWithNullHook() {
    TestResourceService service = new TestResourceService();
    // With super(), shutdownHook is null; invoking register/deregister should be safe no-ops
    service.registerShutdownHook();
    service.deregisterShutdownHook();
    assertFalse(service.isClosed());
  }
}
