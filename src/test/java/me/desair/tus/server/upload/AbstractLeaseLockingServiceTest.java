package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AbstractLeaseLockingServiceTest {

  private TestLeaseLockingService service;

  private static class TestLeaseLockingService extends AbstractLeaseLockingService {
    private boolean acquireShouldSucceed = true;
    private boolean isExpired = false;
    private boolean evictShouldSucceed = true;
    private final List<UploadId> stopSignalsWritten = new ArrayList<>();
    private final List<String> stopSignalsChecked = new ArrayList<>();
    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    public TestLeaseLockingService() {
      super(new UuidUploadIdFactory(), 30000L, 1000L, null, "test-lease-locking-service-watchdog");
    }

    @Override
    protected UploadLock tryAcquireLock(UploadId uploadId, LeaseData leaseData) {
      if (acquireShouldSucceed) {
        return mock(UploadLock.class);
      }
      return null;
    }

    @Override
    protected boolean isLockExpired(UploadId uploadId) {
      return isExpired;
    }

    @Override
    protected boolean evictExpiredLock(UploadId uploadId) {
      return evictShouldSucceed;
    }

    @Override
    protected void writeStopSignal(UploadId uploadId) {
      stopSignalsWritten.add(uploadId);
    }

    @Override
    protected void checkStopSignalForEntry(String uri, InputStream inputStream) {
      stopSignalsChecked.add(uri);
    }

    @Override
    protected void doCleanupOnClose() throws IOException {
      cleanedUp.set(true);
    }

    @Override
    public void cleanupStaleLocks() throws IOException {}

    public void testJitter(long min, long max) {
      applyJitter(min, max);
    }

    public void testCheckStopSignals() {
      checkStopSignals();
    }
  }

  @Before
  public void setUp() {
    service = new TestLeaseLockingService();
  }

  @After
  public void tearDown() throws IOException {
    if (service != null) {
      service.close();
    }
  }

  @Test
  public void testLockUploadByUriWhenNoUploadId() throws Exception {
    UploadLock lock = service.lockUploadByUri("/invalid-uri");
    assertThat(lock, is(nullValue()));
  }

  @Test
  public void testLockUploadByUriDirectAcquisitionSuccess() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    UploadLock lock = service.lockUploadByUri(uri);
    assertThat(lock, is(notNullValue()));
  }

  @Test
  public void testLockUploadByUriEvictAndRetrySuccess() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    service.acquireShouldSucceed = false;
    service.isExpired = true;
    service.evictShouldSucceed = true;

    // After eviction, second acquire succeeds
    service =
        new TestLeaseLockingService() {
          private int acquireAttempts = 0;

          @Override
          protected UploadLock tryAcquireLock(UploadId uploadId, LeaseData leaseData) {
            acquireAttempts++;
            if (acquireAttempts == 1) {
              return null;
            }
            return mock(UploadLock.class);
          }

          @Override
          protected boolean isLockExpired(UploadId uploadId) {
            return true;
          }

          @Override
          protected boolean evictExpiredLock(UploadId uploadId) {
            return true;
          }
        };

    UploadLock lock = service.lockUploadByUri(uri);
    assertThat(lock, is(notNullValue()));
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testLockUploadByUriThrowsWhenNotExpired() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    service.acquireShouldSucceed = false;
    service.isExpired = false;

    service.lockUploadByUri(uri);
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testLockUploadByUriThrowsWhenEvictionFails() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    service.acquireShouldSucceed = false;
    service.isExpired = true;
    service.evictShouldSucceed = false;

    service.lockUploadByUri(uri);
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testLockUploadByUriThrowsWhenRetryFailsAfterEviction() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    service.acquireShouldSucceed = false;
    service.isExpired = true;
    service.evictShouldSucceed = true;

    service.lockUploadByUri(uri);
  }

  @Test
  public void testIsLocked() {
    assertFalse(service.isLocked(null));

    UploadId id = new UploadId(UUID.randomUUID().toString());
    service.isExpired = false;
    assertTrue(service.isLocked(id));

    service.isExpired = true;
    assertFalse(service.isLocked(id));
  }

  @Test
  public void testSetIdFactory() {
    UploadIdFactory customFactory = mock(UploadIdFactory.class);
    service.setIdFactory(customFactory);
  }

  @Test(expected = NullPointerException.class)
  public void testSetIdFactoryNullThrows() {
    service.setIdFactory(null);
  }

  @Test
  public void testRegisterInputStreamAndRequestLockRelease() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));

    service.registerInputStream(null, stream);
    service.registerInputStream(uri, null);
    service.registerInputStream(uri, stream);

    service.requestLockRelease(null);
    assertThat(service.stopSignalsWritten.size(), is(0));

    service.requestLockRelease("/invalid-uri");
    assertThat(service.stopSignalsWritten.size(), is(0));

    service.requestLockRelease(uri);
    assertThat(service.stopSignalsWritten.size(), is(1));
  }

  @Test
  public void testCheckStopSignalsIteratesActiveStreams() {
    String uri1 = "/files/" + UUID.randomUUID();
    String uri2 = "/files/" + UUID.randomUUID();
    service.registerInputStream(uri1, new ByteArrayInputStream("1".getBytes()));
    service.registerInputStream(uri2, new ByteArrayInputStream("2".getBytes()));

    service.testCheckStopSignals();
    assertTrue(service.stopSignalsChecked.contains(uri1));
    assertTrue(service.stopSignalsChecked.contains(uri2));
  }

  @Test
  public void testApplyJitter() {
    service.testJitter(1L, 5L);
  }

  @Test
  public void testCleanupOnClose() throws Exception {
    String uri = "/files/" + UUID.randomUUID();
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    service.registerInputStream(uri, stream);

    service.close();

    assertTrue(service.cleanedUp.get());
    assertTrue(service.isClosed());
  }
}
