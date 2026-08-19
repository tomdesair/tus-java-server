package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AbstractLeaseLockTest {

  private static class TestLeaseLock extends AbstractLeaseLock {
    private final AtomicInteger renewCount = new AtomicInteger(0);
    private final AtomicBoolean released = new AtomicBoolean(false);

    public TestLeaseLock() {
      super();
    }

    public TestLeaseLock(String holderId, String requestUri, long leaseDurationMs, long expiresAt) {
      super(holderId, requestUri, leaseDurationMs, expiresAt);
    }

    public TestLeaseLock(
        String holderId,
        long leaseDurationMs,
        String requestUri,
        Map<String, InputStream> activeInputStreams,
        String watchdogThreadName) {
      super(holderId, leaseDurationMs, requestUri, activeInputStreams, watchdogThreadName);
    }

    public TestLeaseLock(
        String holderId,
        long leaseDurationMs,
        String requestUri,
        Map<String, InputStream> activeInputStreams,
        ScheduledExecutorService heartbeatExecutor,
        String watchdogThreadName) {
      super(
          holderId,
          leaseDurationMs,
          requestUri,
          activeInputStreams,
          heartbeatExecutor,
          watchdogThreadName);
    }

    @Override
    protected void doRenewLease() {
      renewCount.incrementAndGet();
    }

    @Override
    protected void releaseLockResource() {
      released.set(true);
    }
  }

  @Test
  public void testDefaultConstructorAndGettersSetters() {
    TestLeaseLock lock = new TestLeaseLock();
    lock.setHolderId("holder-1");
    lock.setRequestUri("/files/upload-1");
    lock.setLeaseDurationMs(30000L);
    lock.setExpiresAt(100000L);
    lock.setAcquiredAt(50000L);

    assertThat(lock.getHolderId(), is("holder-1"));
    assertThat(lock.getRequestUri(), is("/files/upload-1"));
    assertThat(lock.getUploadUri(), is("/files/upload-1"));
    assertThat(lock.getLeaseDurationMs(), is(30000L));
    assertThat(lock.getExpiresAt(), is(100000L));
    assertThat(lock.getAcquiredAt(), is(50000L));
  }

  @Test
  public void testSerializationConstructor() {
    TestLeaseLock lock = new TestLeaseLock("holder-2", "/files/upload-2", 15000L, 99999L);

    assertThat(lock.getHolderId(), is("holder-2"));
    assertThat(lock.getRequestUri(), is("/files/upload-2"));
    assertThat(lock.getLeaseDurationMs(), is(15000L));
    assertThat(lock.getExpiresAt(), is(99999L));
    assertThat(lock.getAcquiredAt(), greaterThan(0L));
  }

  @Test
  public void testActiveLockConstructorWithAutoWatchdog() throws Exception {
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    activeStreams.put("/files/upload-3", new ByteArrayInputStream("test".getBytes()));

    TestLeaseLock lock =
        new TestLeaseLock("holder-3", 10000L, "/files/upload-3", activeStreams, "test-watchdog");

    assertThat(lock.getHolderId(), is("holder-3"));
    assertThat(lock.getLeaseDurationMs(), is(10000L));
    assertThat(lock.getExpiresAt(), greaterThan(System.currentTimeMillis()));

    long beforeRenew = lock.getExpiresAt();
    Thread.sleep(20L);
    lock.renewLease();

    assertThat(lock.renewCount.get(), is(1));
    assertThat(lock.getExpiresAt(), greaterThan(beforeRenew));

    lock.release();

    assertTrue(lock.released.get());
    assertFalse(activeStreams.containsKey("/files/upload-3"));
  }

  @Test
  public void testActiveLockConstructorWithCustomExecutor() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    TestLeaseLock lock =
        new TestLeaseLock(
            "holder-4", 10000L, "/files/upload-4", activeStreams, mockExecutor, "test-watchdog");

    assertNotNull(lock);
    lock.close();

    verify(mockExecutor).shutdownNow();
    assertTrue(lock.released.get());
  }

  @Test
  public void testActiveLockConstructorWithZeroLeaseDurationDoesNotSchedule() {
    TestLeaseLock lock =
        new TestLeaseLock("holder-5", 0L, "/files/upload-5", null, "test-watchdog");

    lock.close();
    assertTrue(lock.released.get());

    TestLeaseLock lockNullWatchdog =
        new TestLeaseLock("holder-5b", 10000L, "/files/upload-5b", null, null);
    lockNullWatchdog.close();
    assertTrue(lockNullWatchdog.released.get());
  }

  @Test
  public void testCloseWithNullStreamsOrNullUri() {
    TestLeaseLock lockNullStreams =
        new TestLeaseLock("holder-6", 10000L, "/files/upload-6", null, "test-watchdog");
    lockNullStreams.close();
    assertTrue(lockNullStreams.released.get());

    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    TestLeaseLock lockNullUri =
        new TestLeaseLock("holder-7", 10000L, null, activeStreams, "test-watchdog");
    lockNullUri.close();
    assertTrue(lockNullUri.released.get());
  }
}
