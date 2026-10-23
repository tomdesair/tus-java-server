package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.Test;

public class AbstractLeaseLockTest {

  private static class TestLeaseLock extends AbstractLeaseLock {
    private final AtomicInteger renewCount = new AtomicInteger(0);
    private final AtomicBoolean released = new AtomicBoolean(false);

    public TestLeaseLock(
        LeaseData leaseData,
        UploadId uploadId,
        Map<UploadId, InputStream> activeInputStreams,
        ScheduledExecutorService heartbeatExecutor) {
      super(leaseData, uploadId, activeInputStreams, heartbeatExecutor);
    }

    public TestLeaseLock(
        LeaseData leaseData, UploadId uploadId, Map<UploadId, InputStream> activeInputStreams) {
      this(leaseData, uploadId, activeInputStreams, null);
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
    UploadId uploadId = new UploadId("upload-1");
    LeaseData leaseData =
        new LeaseData("holder-1", "/files/upload-1", 30000L, 100000L, 50000L, "/path", "/stop");
    TestLeaseLock lock = new TestLeaseLock(leaseData, uploadId, null, null);

    assertThat(lock.getHolderId(), is("holder-1"));
    assertThat(lock.getUploadId(), is(uploadId));
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
  public void testActiveLockConstructorWithExecutor() throws Exception {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture mockFuture = mock(ScheduledFuture.class);
    when(mockExecutor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenReturn(mockFuture);

    UploadId uploadId = new UploadId("upload-3");
    Map<UploadId, InputStream> activeStreams = new ConcurrentHashMap<>();
    activeStreams.put(uploadId, new ByteArrayInputStream("test".getBytes()));

    LeaseData leaseData =
        new LeaseData("holder-3", "/files/upload-3", 10000L, System.currentTimeMillis() + 10000L);
    TestLeaseLock lock = new TestLeaseLock(leaseData, uploadId, activeStreams, mockExecutor);

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
    assertFalse(activeStreams.containsKey(uploadId));
    verify(mockFuture).cancel(true);
  }

  @Test
  public void testActiveLockConstructorWithZeroLeaseDurationDoesNotSchedule() {
    UploadId uploadId = new UploadId("upload-5");
    LeaseData leaseData = new LeaseData("holder-5", "/files/upload-5", 0L, 0L);
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    TestLeaseLock lock = new TestLeaseLock(leaseData, uploadId, null, mockExecutor);

    lock.close();
    assertTrue(lock.released.get());
    org.mockito.Mockito.verifyNoInteractions(mockExecutor);

    LeaseData leaseData2 = new LeaseData("holder-5b", "/files/upload-5b", 10000L, 10000L);
    TestLeaseLock lockNullExecutor = new TestLeaseLock(leaseData2, uploadId, null, null);
    lockNullExecutor.close();
    assertTrue(lockNullExecutor.released.get());
  }

  @Test
  public void testCloseWithNullStreamsOrNullUploadId() {
    UploadId uploadId = new UploadId("upload-6");
    LeaseData leaseData = new LeaseData("holder-6", "/files/upload-6", 10000L, 10000L);
    TestLeaseLock lockNullStreams = new TestLeaseLock(leaseData, uploadId, null);
    lockNullStreams.close();
    assertTrue(lockNullStreams.released.get());

    Map<UploadId, InputStream> activeStreams = new ConcurrentHashMap<>();
    LeaseData leaseDataNullId = new LeaseData("holder-7", null, 10000L, 10000L);
    TestLeaseLock lockNullUploadId = new TestLeaseLock(leaseDataNullId, null, activeStreams);
    lockNullUploadId.close();
    assertTrue(lockNullUploadId.released.get());
  }

  @Test
  public void testRenewLeaseFailureInterruptsActiveStreamAndCancelsHeartbeat() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture mockFuture = mock(ScheduledFuture.class);
    when(mockExecutor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenReturn(mockFuture);
    UploadId uploadId = new UploadId("upload-failing");
    Map<UploadId, InputStream> activeStreams = new ConcurrentHashMap<>();

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("test".getBytes()));
    activeStreams.put(uploadId, stream);

    LeaseData leaseData =
        new LeaseData(
            "holder-fail", "/files/upload-failing", 10000L, System.currentTimeMillis() - 1000L);

    TestLeaseLock lock =
        new TestLeaseLock(leaseData, uploadId, activeStreams, mockExecutor) {
          @Override
          protected void doRenewLease() {
            throw new RuntimeException("Simulated S3 network failure during lease renewal");
          }
        };

    // Trigger renewLease and verify that active streaming input stream was interrupted
    // immediately to prevent un-locked writes, and heartbeat daemon cancelled.
    lock.renewLease();

    assertTrue(stream.isInterrupted());
    verify(mockFuture).cancel(true);
  }

  @Test
  public void testRenewLeaseTransientFailureDoesNotInterruptActiveStreamIfLeaseValid() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture mockFuture = mock(ScheduledFuture.class);
    when(mockExecutor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenReturn(mockFuture);
    UploadId uploadId = new UploadId("upload-transient");
    Map<UploadId, InputStream> activeStreams = new ConcurrentHashMap<>();

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("test".getBytes()));
    activeStreams.put(uploadId, stream);

    LeaseData leaseData =
        new LeaseData(
            "holder-fail", "/files/upload-failing", 10000L, System.currentTimeMillis() + 10000L);

    TestLeaseLock lock =
        new TestLeaseLock(leaseData, uploadId, activeStreams, mockExecutor) {
          @Override
          protected void doRenewLease() {
            throw new RuntimeException("Simulated transient network glitch");
          }
        };

    // Trigger renewLease and verify that transient glitch does not abort while lease is still valid
    lock.renewLease();

    org.junit.Assert.assertFalse(stream.isInterrupted());
    org.mockito.Mockito.verify(mockExecutor, org.mockito.Mockito.never()).shutdownNow();
    org.mockito.Mockito.verify(mockFuture, org.mockito.Mockito.never()).cancel(true);
  }

  @Test
  public void testRenewLeaseOwnershipLostInterruptsActiveStreamAndCancelsHeartbeat() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture mockFuture = mock(ScheduledFuture.class);
    when(mockExecutor.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
        .thenReturn(mockFuture);
    UploadId uploadId = new UploadId("upload-lost");
    Map<UploadId, InputStream> activeStreams = new ConcurrentHashMap<>();

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("test".getBytes()));
    activeStreams.put(uploadId, stream);

    LeaseData leaseData =
        new LeaseData(
            "holder-lost", "/files/upload-lost", 10000L, System.currentTimeMillis() + 10000L);

    TestLeaseLock lock =
        new TestLeaseLock(leaseData, uploadId, activeStreams, mockExecutor) {
          @Override
          protected void doRenewLease() {
            throw new IllegalStateException("Lease taken over by rival holder");
          }
        };

    lock.renewLease();

    assertTrue(stream.isInterrupted());
    verify(mockFuture).cancel(true);
  }
}
