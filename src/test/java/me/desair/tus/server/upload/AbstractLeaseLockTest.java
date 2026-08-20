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

    public TestLeaseLock(
        LeaseData leaseData,
        Map<String, InputStream> activeInputStreams,
        String watchdogThreadName) {
      super(leaseData, activeInputStreams, watchdogThreadName);
    }

    public TestLeaseLock(
        LeaseData leaseData,
        Map<String, InputStream> activeInputStreams,
        ScheduledExecutorService heartbeatExecutor,
        String watchdogThreadName) {
      super(leaseData, activeInputStreams, heartbeatExecutor, watchdogThreadName);
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
  public void testGettersAndSetters() {
    LeaseData leaseData =
        new LeaseData("holder-1", "/files/upload-1", 30000L, 100000L, 50000L, "/path", "/stop");
    TestLeaseLock lock = new TestLeaseLock(leaseData, null, null);

    assertThat(lock.getHolderId(), is("holder-1"));
    assertThat(lock.getRequestUri(), is("/files/upload-1"));
    assertThat(lock.getUploadUri(), is("/files/upload-1"));
    assertThat(lock.getLeaseDurationMs(), is(30000L));
    assertThat(lock.getExpiresAt(), is(100000L));
    assertThat(lock.getAcquiredAt(), is(50000L));
    assertThat(lock.getLeaseData(), is(leaseData));

    lock.setExpiresAt(200000L);
    assertThat(lock.getExpiresAt(), is(200000L));
  }

  @Test
  public void testActiveLockConstructorWithAutoWatchdog() throws Exception {
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    activeStreams.put("/files/upload-3", new ByteArrayInputStream("test".getBytes()));

    LeaseData leaseData =
        new LeaseData("holder-3", "/files/upload-3", 10000L, System.currentTimeMillis() + 10000L);
    TestLeaseLock lock = new TestLeaseLock(leaseData, activeStreams, "test-watchdog");

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

    LeaseData leaseData =
        new LeaseData("holder-4", "/files/upload-4", 10000L, System.currentTimeMillis() + 10000L);
    TestLeaseLock lock = new TestLeaseLock(leaseData, activeStreams, mockExecutor, "test-watchdog");

    assertNotNull(lock);
    lock.close();

    verify(mockExecutor).shutdownNow();
    assertTrue(lock.released.get());
  }

  @Test
  public void testActiveLockConstructorWithZeroLeaseDurationDoesNotSchedule() {
    LeaseData leaseData = new LeaseData("holder-5", "/files/upload-5", 0L, 0L);
    TestLeaseLock lock = new TestLeaseLock(leaseData, null, "test-watchdog");

    lock.close();
    assertTrue(lock.released.get());

    LeaseData leaseData2 = new LeaseData("holder-5b", "/files/upload-5b", 10000L, 10000L);
    TestLeaseLock lockNullWatchdog = new TestLeaseLock(leaseData2, null, null);
    lockNullWatchdog.close();
    assertTrue(lockNullWatchdog.released.get());
  }

  @Test
  public void testCloseWithNullStreamsOrNullUri() {
    LeaseData leaseData = new LeaseData("holder-6", "/files/upload-6", 10000L, 10000L);
    TestLeaseLock lockNullStreams = new TestLeaseLock(leaseData, null, "test-watchdog");
    lockNullStreams.close();
    assertTrue(lockNullStreams.released.get());

    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    LeaseData leaseDataNullUri = new LeaseData("holder-7", null, 10000L, 10000L);
    TestLeaseLock lockNullUri = new TestLeaseLock(leaseDataNullUri, activeStreams, "test-watchdog");
    lockNullUri.close();
    assertTrue(lockNullUri.released.get());
  }
}
