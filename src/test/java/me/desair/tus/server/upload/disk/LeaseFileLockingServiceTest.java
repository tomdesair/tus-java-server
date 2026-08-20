package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

/**
 * Comprehensive unit test suite for {@link LeaseFileLockingService} verifying atomic directory
 * acquisition, TTL expiration, grace period handling, stale lock sweeps, and thread contention.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class LeaseFileLockingServiceTest {

  public static final String UPLOAD_URL = "/upload/test";
  private LeaseFileLockingService lockingService;

  @Mock private UploadIdFactory idFactory;

  private static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "lease-locking-test").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Before
  public void setUp() {
    reset(idFactory);
    when(idFactory.getUploadUri()).thenReturn(UPLOAD_URL);
    when(idFactory.createId()).thenReturn(new UploadId(UUID.randomUUID()));
    when(idFactory.readUploadId(nullable(String.class)))
        .then(
            new Answer<UploadId>() {
              @Override
              public UploadId answer(InvocationOnMock invocation) throws Throwable {
                Object arg = invocation.getArguments()[0];
                if (arg == null) {
                  return null;
                }
                String argStr = arg.toString();
                if (!argStr.contains(UPLOAD_URL + "/")) {
                  return null;
                }
                return new UploadId(StringUtils.substringAfter(argStr, UPLOAD_URL + "/"));
              }
            });

    lockingService = new LeaseFileLockingService(idFactory, storagePath.toString());
  }

  @After
  public void tearDown() throws Exception {
    if (lockingService != null) {
      lockingService.close();
    }
  }

  @Test
  public void testLockAcquireCreatesDirectoryAndLeaseFile() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);
    assertEquals(uri, lock.getUploadUri());

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    assertTrue(Files.exists(lockDir));
    assertTrue(Files.isDirectory(lockDir));

    Path leaseFile = lockDir.resolve("lease.json");
    assertTrue(Files.exists(leaseFile));

    LeaseData lease = LeaseDataJsonSerializer.deserialize(leaseFile);
    assertNotNull(lease);
    assertNotNull(lease.getHolderId());
    assertTrue(lease.getExpiresAt() > System.currentTimeMillis());

    lock.close();
    assertFalse(Files.exists(lockDir));
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testDoubleLockThrowsUploadAlreadyLockedException() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    UploadLock lock1 = lockingService.lockUploadByUri(uri);
    assertNotNull(lock1);

    try {
      // Second lock attempt must fail
      lockingService.lockUploadByUri(uri);
    } finally {
      lock1.close();
    }
  }

  @Test
  public void testExpiredLeaseEvictedAndReacquired() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);

    // Write an already-expired lease.json
    long pastTime = System.currentTimeMillis() - 10_000L;
    LeaseData expiredLease =
        createExpiredLease("expired-holder", uri, lockDir.toString(), pastTime);
    LeaseDataJsonSerializer.serializeToPath(expiredLease, lockDir.resolve("lease.json"));
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(pastTime));

    // Contender acquires lock: expired directory should be evicted and new lock acquired
    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);

    LeaseData activeLease = LeaseDataJsonSerializer.deserialize(lockDir.resolve("lease.json"));
    assertNotNull(activeLease);
    assertEquals(activeLease.getHolderId(), ((LeaseFileUploadLock) lock).getHolderId());
    assertTrue(activeLease.getExpiresAt() > System.currentTimeMillis());

    lock.close();
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testEmptyLockDirectoryWithinGracePeriodThrowsLocked() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);
    // Set directory mtime to now (within 5s grace period)
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(System.currentTimeMillis()));

    // Must be treated as actively being written by another node
    lockingService.lockUploadByUri(uri);
  }

  @Test
  public void testEmptyLockDirectoryAfterGracePeriodEvicted() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);
    // Set directory mtime to 10 seconds ago (beyond 5s grace period)
    long tenSecondsAgo = System.currentTimeMillis() - 10_000L;
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(tenSecondsAgo));

    // Empty lock directory older than 5s grace period: treat as abandoned crash and evict
    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);
    lock.close();
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testCorruptedLeaseFileWithinGracePeriodThrowsLocked() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);
    Files.write(lockDir.resolve("lease.json"), "invalid-json-content-{{{".getBytes());
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(System.currentTimeMillis()));

    // Within 5s grace period: treat corrupted lease.json as active write in progress
    lockingService.lockUploadByUri(uri);
  }

  @Test
  public void testCorruptedLeaseFileAfterGracePeriodEvicted() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);
    Files.write(lockDir.resolve("lease.json"), "invalid-json-content-{{{".getBytes());
    long tenSecondsAgo = System.currentTimeMillis() - 10_000L;
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(tenSecondsAgo));

    // After grace period: treat as abandoned crash and evict
    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);

    LeaseData lease = LeaseDataJsonSerializer.deserialize(lockDir.resolve("lease.json"));
    assertNotNull(lease);

    lock.close();
  }

  @Test
  public void testIsLockedReturnsTrueForActiveLease() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;
    UploadId uploadId = new UploadId(uploadIdStr);

    assertFalse(lockingService.isLocked(uploadId));
    assertFalse(lockingService.isLocked(null));

    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertTrue(lockingService.isLocked(uploadId));

    lock.close();
    assertFalse(lockingService.isLocked(uploadId));
  }

  @Test
  public void testCleanupStaleLocksRemovesExpiredOnly() throws Exception {
    String activeIdStr = UUID.randomUUID().toString();
    String expiredIdStr = UUID.randomUUID().toString();
    String staleStopIdStr = UUID.randomUUID().toString();

    // 1. Create active lock
    UploadLock activeLock = lockingService.lockUploadByUri(UPLOAD_URL + "/" + activeIdStr);

    // 2. Create expired lock directory
    Path expiredLockDir = storagePath.resolve("locks").resolve(expiredIdStr + ".lock");
    Files.createDirectories(expiredLockDir);
    long pastTime = System.currentTimeMillis() - 10_000L;
    LeaseData expiredLease =
        createExpiredLease(
            "expired", UPLOAD_URL + "/" + expiredIdStr, expiredLockDir.toString(), pastTime);
    LeaseDataJsonSerializer.serializeToPath(expiredLease, expiredLockDir.resolve("lease.json"));

    // 3. Create stale .stop file
    Path staleStopFile = storagePath.resolve("locks").resolve(staleStopIdStr + ".stop");
    Files.write(staleStopFile, new byte[0]);
    Files.setLastModifiedTime(
        staleStopFile, FileTime.fromMillis(System.currentTimeMillis() - 20_000L));

    // Run cleanup
    lockingService.cleanupStaleLocks();

    // Verify active lock still exists, expired lock and stale stop file were removed
    Path activeLockDir = storagePath.resolve("locks").resolve(activeIdStr + ".lock");
    assertTrue(Files.exists(activeLockDir));
    assertFalse(Files.exists(expiredLockDir));
    assertFalse(Files.exists(staleStopFile));

    activeLock.close();
  }

  @Test
  public void testRequestLockReleaseInterruptsLocalStreamAndWritesStopFile() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    lockingService.registerInputStream(uri, stream);

    lockingService.requestLockRelease(uri);

    // Stream should be closed / interrupted
    assertTrue(stream.isInterrupted());

    // Stop signal file should be written to locks directory
    Path stopFile = storagePath.resolve("locks").resolve(uploadIdStr + ".stop");
    assertTrue(Files.exists(stopFile));

    Files.deleteIfExists(stopFile);
  }

  @Test
  public void testRequestLockReleaseWithNullUriShouldBeNoOp() {
    lockingService.requestLockRelease(null);
    lockingService.registerInputStream(null, null);
  }

  @Test
  public void testWatchdogInterruptsStreamOnStopFile() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("test".getBytes()));
    lockingService.registerInputStream(uri, stream);

    // Write .stop file simulating a remote replica requesting release
    Path stopFile = storagePath.resolve("locks").resolve(uploadIdStr + ".stop");
    Utils.ensureDirectoryExists(stopFile.getParent());
    Files.write(stopFile, new byte[0]);

    // Wait up to 3 seconds for the watchdog thread (1.5s poll) to detect and interrupt
    long deadline = System.currentTimeMillis() + 3500L;
    while (!stream.isInterrupted() && System.currentTimeMillis() < deadline) {
      Thread.sleep(100L);
    }

    assertTrue(stream.isInterrupted());
    assertFalse(Files.exists(stopFile));
  }

  @Test
  public void testThreadContentionSingleWinner() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    // Simulate 20 concurrent threads trying to acquire a lock on the exact same upload URI
    int threadCount = 20;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger lockedExceptionCount = new AtomicInteger(0);
    List<UploadLock> acquiredLocks = new java.util.concurrent.CopyOnWriteArrayList<>();

    // startLatch ensures all 20 threads are primed and attempt lock acquisition simultaneously
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

    for (int i = 0; i < threadCount; i++) {
      tasks.add(
          () -> {
            try {
              // Wait until all threads are ready to maximize concurrent contention
              startLatch.await();
              UploadLock lock = lockingService.lockUploadByUri(uri);
              if (lock != null) {
                successCount.incrementAndGet();
                // Retain active lock handle until all tasks in the pool have completed,
                // preventing trailing threads from acquiring after a premature close.
                acquiredLocks.add(lock);
                return true;
              }
            } catch (UploadAlreadyLockedException e) {
              // Losing contenders must catch UploadAlreadyLockedException
              lockedExceptionCount.incrementAndGet();
            }
            return false;
          });
    }

    // Release all 20 threads simultaneously
    startLatch.countDown();
    List<Future<Boolean>> futures = executor.invokeAll(tasks);
    executor.shutdown();

    // Invariant: Exactly 1 thread must succeed, and 19 must receive UploadAlreadyLockedException
    assertEquals(1, successCount.get());
    assertEquals(19, lockedExceptionCount.get());
    assertEquals(1, acquiredLocks.size());

    // Clean up acquired locks
    for (UploadLock lock : acquiredLocks) {
      lock.close();
    }
    lockingService.cleanupStaleLocks();
    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    if (Files.exists(lockDir)) {
      FileUtils.deleteDirectory(lockDir.toFile());
    }
  }

  @Test
  public void testConcurrentEvictionContention() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    // Create an expired lock directory to simulate a crashed upload node left behind
    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);
    long pastTime = System.currentTimeMillis() - 10_000L;
    LeaseData expiredLease = createExpiredLease("expired", uri, lockDir.toString(), pastTime);
    LeaseDataJsonSerializer.serializeToPath(expiredLease, lockDir.resolve("lease.json"));
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(pastTime));

    // Simulate 10 concurrent threads simultaneously discovering the expired lock
    // and racing to evict it and acquire a fresh lock.
    //
    // TOCTOU (Time-of-Check to Time-of-Use) explanation:
    // Without post-move verification in atomicEvictExpiredLock:
    // 1. Thread A & Thread B both check that the lock directory is expired (Time of Check: true).
    // 2. Thread A renames the directory, deletes it, and creates a brand-new active lock.
    // 3. Thread B (having already checked expiration earlier) renames Thread A's NEW lock directory
    //    and deletes it (Time of Use), then acquires a second lock handle.
    // 4. Result: Both Thread A and Thread B believe they hold exclusive ownership (successCount =
    // 2).
    //
    // With post-move verification in atomicEvictExpiredLock:
    // - When Thread B renames the directory, it inspects the isolated directory (evictPath)
    // post-move.
    // - Thread B discovers that evictPath contains Thread A's active lease, restores it back to
    //   lockDirPath, and aborts eviction.
    // - Result: Exactly 1 thread wins the eviction and acquisition (successCount = 1).
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    List<Callable<Boolean>> tasks = new ArrayList<>();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger lockedExceptionCount = new AtomicInteger(0);
    List<UploadLock> acquiredLocks = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

    List<Throwable> unexpectedErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

    for (int i = 0; i < threadCount; i++) {
      tasks.add(
          () -> {
            try {
              // Wait until all 10 threads are primed
              startLatch.await();
              UploadLock lock = lockingService.lockUploadByUri(uri);
              if (lock != null) {
                successCount.incrementAndGet();
                // Retain active lock handle until all tasks in the pool have completed,
                // preventing trailing threads from acquiring after a premature close.
                acquiredLocks.add(lock);
                return true;
              }
            } catch (UploadAlreadyLockedException e) {
              lockedExceptionCount.incrementAndGet();
            } catch (Throwable t) {
              unexpectedErrors.add(t);
            }
            return false;
          });
    }

    // Release all 10 threads simultaneously to race for eviction and acquisition
    startLatch.countDown();
    List<Future<Boolean>> futures = executor.invokeAll(tasks);
    executor.shutdown();

    assertTrue("Unexpected errors: " + unexpectedErrors, unexpectedErrors.isEmpty());

    // Invariant: Exactly 1 thread must succeed in evicting and reacquiring the lock,
    // and 9 must receive UploadAlreadyLockedException without corrupting the winning lease.
    assertEquals(1, successCount.get());
    assertEquals(9, lockedExceptionCount.get());
    assertEquals(1, acquiredLocks.size());

    // Clean up acquired locks
    for (UploadLock lock : acquiredLocks) {
      lock.close();
    }
    if (Files.exists(lockDir)) {
      FileUtils.deleteDirectory(lockDir.toFile());
    }
  }

  @Test
  public void testConstructorsAndSetIdFactory() {
    LeaseFileLockingService s1 = new LeaseFileLockingService(storagePath.toString());
    assertNotNull(s1);

    LeaseFileLockingService s2 = new LeaseFileLockingService(storagePath.toString(), 15000L, 1000L);
    assertNotNull(s2);

    s1.setIdFactory(new UuidUploadIdFactory());

    try {
      s1.close();
      s2.close();
    } catch (Exception ignored) {
    }
  }

  @Test
  public void testLockUploadByUriWithNullOrUnmatchedUri() throws Exception {
    assertNull(lockingService.lockUploadByUri(null));
    assertNull(lockingService.lockUploadByUri("/unmatched/uri"));
  }

  @Test
  public void testCleanupStaleLocksWithNonExistentStorageDirectory() throws Exception {
    Path nonExistent = storagePath.resolve("non-existent-storage-" + UUID.randomUUID());
    LeaseFileLockingService service = new LeaseFileLockingService(nonExistent.toString());
    // Calling cleanupStaleLocks on non-existent directory should return cleanly
    service.cleanupStaleLocks();
    service.close();
  }

  @Test
  public void testWatchdogWithUnmatchedUriShouldNotFail() throws Exception {
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    // Register stream with URI that does not match idFactory pattern
    lockingService.registerInputStream("/invalid/uri/format", stream);

    // Write a dummy stop file
    Path stopFile = storagePath.resolve("locks").resolve("dummy.stop");
    Files.write(stopFile, new byte[0]);

    // Give watchdog a moment to run checkStopSignals
    Thread.sleep(200L);

    Files.deleteIfExists(stopFile);
    stream.close();
  }

  @Test
  public void testCleanupOnCloseInterruptsActiveStreams() throws Exception {
    LeaseFileLockingService service = new LeaseFileLockingService(storagePath.toString());
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    service.registerInputStream(UPLOAD_URL + "/close-test", stream);

    service.close();

    assertTrue(stream.isInterrupted());
  }

  @Test
  public void testIsLockedWithNullOrMissingId() {
    assertFalse(lockingService.isLocked(null));
    assertFalse(lockingService.isLocked(new UploadId("non-existent-upload-id")));
  }

  @Test
  public void testCleanupStaleLocksRetainsRecentStopFile() throws Exception {
    Path recentStopFile =
        storagePath.resolve("locks").resolve("recent-" + UUID.randomUUID() + ".stop");
    Utils.ensureDirectoryExists(recentStopFile.getParent());
    Files.write(recentStopFile, new byte[0]);
    Files.setLastModifiedTime(recentStopFile, FileTime.fromMillis(System.currentTimeMillis()));

    lockingService.cleanupStaleLocks();

    // Recent stop file (age < 10s) must NOT be deleted
    assertTrue(Files.exists(recentStopFile));

    Files.deleteIfExists(recentStopFile);
  }

  @Test
  public void testRequestLockReleaseWithUnmatchedUriDoesNotThrow() {
    lockingService.requestLockRelease("/no/match/uri");
  }

  @Test
  public void testGetLockDirPathAndStopFilePathWithNull() {
    assertNull(lockingService.getLockDirPath(null));
    assertNull(lockingService.getStopFilePath(null));
    assertNotNull(lockingService.getLockDirPath(new UploadId("test-id")));
    assertNotNull(lockingService.getStopFilePath(new UploadId("test-id")));
  }

  @Test
  public void testCleanupStaleLocksWhenDirectoryDeleted() throws Exception {
    Path tempStorage = storagePath.resolve("temp-cleanup-" + UUID.randomUUID());
    LeaseFileLockingService service = new LeaseFileLockingService(tempStorage.toString());
    // Delete the locks directory
    FileUtils.deleteDirectory(service.getStoragePath().toFile());

    // Calling cleanupStaleLocks when directory doesn't exist should return cleanly
    service.cleanupStaleLocks();
    service.close();
  }

  @Test
  public void testCleanupStaleLocksWithVariousFileTypes() throws Exception {
    Path locksDir = storagePath.resolve("locks");
    Utils.ensureDirectoryExists(locksDir);

    // 1. Regular file ending with .lock (should NOT be treated as lock directory)
    Path regularLockFile = locksDir.resolve("not-a-dir-" + UUID.randomUUID() + ".lock");
    Files.write(regularLockFile, "data".getBytes());

    // 2. Directory ending with .stop (should NOT be treated as stop regular file)
    Path stopDir = locksDir.resolve("not-a-file-" + UUID.randomUUID() + ".stop");
    Files.createDirectory(stopDir);

    // 3. Regular file with unrelated extension
    Path unrelatedFile = locksDir.resolve("unrelated-" + UUID.randomUUID() + ".txt");
    Files.write(unrelatedFile, "txt".getBytes());

    // 4. Stale .stop file older than 10 seconds (should be deleted)
    Path staleStopFile = locksDir.resolve("stale-" + UUID.randomUUID() + ".stop");
    Files.write(staleStopFile, new byte[0]);
    Files.setLastModifiedTime(
        staleStopFile, FileTime.fromMillis(System.currentTimeMillis() - 20_000L));

    lockingService.cleanupStaleLocks();

    assertTrue(Files.exists(regularLockFile));
    assertTrue(Files.exists(stopDir));
    assertTrue(Files.exists(unrelatedFile));
    assertFalse(Files.exists(staleStopFile));

    Files.deleteIfExists(regularLockFile);
    Files.deleteIfExists(stopDir);
    Files.deleteIfExists(unrelatedFile);
  }

  @Test
  public void testRegisterInputStreamNullHandling() {
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    // All combinations of null should be safe no-ops
    lockingService.registerInputStream(null, stream);
    lockingService.registerInputStream(UPLOAD_URL + "/null-test", null);
    lockingService.registerInputStream(null, null);
  }

  @Test
  public void testRequestLockReleaseInterruptsStreamAndWritesStopSignal() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    lockingService.registerInputStream(uri, stream);

    lockingService.requestLockRelease(uri);

    assertTrue(stream.isInterrupted());
    Path stopFile = storagePath.resolve("locks").resolve(uploadIdStr + ".stop");
    assertTrue(Files.exists(stopFile));

    Files.deleteIfExists(stopFile);
  }

  @Test
  public void testCheckStopSignalsWithUnmatchedUriDirect() {
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    lockingService.registerInputStream("/invalid/uri/format", stream);

    // Directly invoking checkStopSignals when uri cannot be resolved by idFactory
    lockingService.checkStopSignals();
  }

  @Test
  public void testWriteStopSignalWithNullOrInvalidPath() {
    // Null upload ID should be a safe no-op
    lockingService.writeStopSignal(null);

    // Valid upload ID should create the stop file
    UploadId id = new UploadId("test-stop-signal");
    lockingService.writeStopSignal(id);
    Path stopFile = lockingService.getStopFilePath(id);
    assertTrue(Files.exists(stopFile));

    try {
      Files.deleteIfExists(stopFile);
    } catch (Exception ignored) {
    }
  }

  @Test
  public void testCorruptedLeaseFileTriggersGracePeriodFallback() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);

    // Write corrupted non-JSON content to lease.json
    Path leaseFile = lockDir.resolve("lease.json");
    Files.write(leaseFile, "NOT_VALID_JSON".getBytes());

    // Setting mtime to 10 seconds ago ensures grace period has passed
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));

    // isLocked should return false (expired) after grace period
    assertFalse(lockingService.isLocked(new UploadId(uploadIdStr)));

    FileUtils.deleteDirectory(lockDir.toFile());
  }

  @Test
  public void testUnreadableLeaseFileAsDirectoryTriggersGracePeriodFallback() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);

    // Create a directory where lease.json is expected to be a file, triggering IOException on read
    Path leaseDir = lockDir.resolve("lease.json");
    Files.createDirectories(leaseDir);

    // Setting mtime to 10 seconds ago ensures grace period has passed
    Files.setLastModifiedTime(lockDir, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));

    // isLocked should return false (expired) after grace period
    assertFalse(lockingService.isLocked(new UploadId(uploadIdStr)));

    FileUtils.deleteDirectory(lockDir.toFile());
  }

  @Test
  public void testAtomicEvictExpiredLockOnActiveLockReturnsFalse() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;

    // Acquire an active, unexpired lock
    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);

    Path lockDir = lockingService.getLockDirPath(new UploadId(uploadIdStr));

    // Verify that atomicEvictExpiredLock checks both pre-move and post-move expiration:
    // Attempting atomic eviction on an active, unexpired lock directory must return false
    // and must never destroy the active lock directory or its lease metadata.
    boolean evicted = lockingService.atomicEvictExpiredLock(lockDir);
    assertFalse(evicted);
    assertTrue(Files.exists(lockDir));

    lock.close();
  }

  @Test
  public void testAtomicEvictExpiredLockPostMoveRollbackOnActiveLease() throws Exception {
    String uploadIdStr = UUID.randomUUID().toString();
    String uri = UPLOAD_URL + "/" + uploadIdStr;
    Path lockDir = storagePath.resolve("locks").resolve(uploadIdStr + ".lock");
    Files.createDirectories(lockDir);

    // Write an active unexpired lease into lockDir
    long futureTime = System.currentTimeMillis() + 30_000L;
    LeaseData activeLease =
        createExpiredLease("active-holder", uri, lockDir.toString(), futureTime);
    LeaseDataJsonSerializer.serializeToPath(activeLease, lockDir.resolve("lease.json"));

    // Create a service subclass to simulate a TOCTOU race:
    // 1. Time-of-Check (pre-check in atomicEvictExpiredLock): simulates observing an expired lock
    // before another node's write
    // 2. Time-of-Use (post-move check in atomicEvictExpiredLock): accurately inspects evictPath and
    // finds the active lease
    AtomicInteger checkCount = new AtomicInteger(0);
    LeaseFileLockingService serviceWithRaceSimulation =
        new LeaseFileLockingService(idFactory, storagePath.toString()) {
          @Override
          boolean isLockDirectoryExpired(Path dir, long now) {
            if (checkCount.incrementAndGet() == 1) {
              // Simulate stale pre-check returning true (expired)
              return true;
            }
            // Real post-move check on evictPath
            return super.isLockDirectoryExpired(dir, now);
          }
        };

    // atomicEvictExpiredLock MUST detect the active lease post-move, roll back the move,
    // restore lockDir to its original location, and return false
    boolean evicted = serviceWithRaceSimulation.atomicEvictExpiredLock(lockDir);
    assertFalse(evicted);
    assertTrue(Files.exists(lockDir));
    assertTrue(Files.exists(lockDir.resolve("lease.json")));

    serviceWithRaceSimulation.close();
    FileUtils.deleteDirectory(lockDir.toFile());
  }

  @Test
  public void testWriteStopSignalWhenStopFileCannotBeWritten() throws Exception {
    UploadId id = new UploadId("test-unwritable-stop-" + UUID.randomUUID());
    Path stopPath = lockingService.getStopFilePath(id);

    // Create a directory where the stop file should be written to force an IOException in
    // Files.write
    Files.createDirectories(stopPath);

    // writeStopSignal catches IOException and logs warning
    lockingService.writeStopSignal(id);
    assertTrue(Files.isDirectory(stopPath));

    FileUtils.deleteDirectory(stopPath.toFile());
  }

  @Test
  public void testConstructorsAndNullChecks() throws Exception {
    LeaseFileLockingService s1 = new LeaseFileLockingService(storagePath.toString());
    assertNotNull(s1.getStoragePath());
    s1.close();

    LeaseFileLockingService s2 = new LeaseFileLockingService(storagePath.toString(), 20000L, 1000L);
    assertNotNull(s2.getStoragePath());
    s2.close();

    LeaseFileLockingService s3 =
        new LeaseFileLockingService(
            new UuidUploadIdFactory(), storagePath.toString(), 20000L, 1000L);
    assertNotNull(s3.getStoragePath());
    s3.close();

    assertTrue(lockingService.isLockExpired(null));
    assertFalse(lockingService.evictExpiredLock(null));
    assertFalse(lockingService.atomicEvictExpiredLock(null));
    assertTrue(lockingService.isLockDirectoryExpired(null, System.currentTimeMillis()));
    assertNull(lockingService.getLockDirPath(null));
    assertNull(lockingService.getStopFilePath(null));
  }

  @Test(expected = StoragePathNotAvailableException.class)
  public void testInitStoragePathThrowsWhenPathIsFile() throws Exception {
    Path filePath = storagePath.resolve("a-regular-file");
    Files.write(filePath, new byte[0]);
    // Passing a path inside a regular file will fail mkdirs
    new LeaseFileLockingService(filePath.resolve("sub-dir").toString());
  }

  @Test
  public void testTryAcquireLockWhenParentCannotBeCreated() throws Exception {
    Path regularFile = storagePath.resolve("blocking-file");
    Files.write(regularFile, new byte[0]);

    LeaseFileLockingService service =
        new LeaseFileLockingService(idFactory, storagePath.toString()) {
          @Override
          Path getLockDirPath(UploadId id) {
            return regularFile.resolve("child.lock");
          }
        };

    LeaseData leaseData =
        new LeaseData(
            "holder-1", "/files/upload/test-id", 30000L, System.currentTimeMillis() + 30000L);
    UploadLock lock = service.tryAcquireLock(new UploadId("test-id"), leaseData);
    assertNull(lock);
    service.close();
  }

  private LeaseData createExpiredLease(
      String holderId, String uri, String storagePath, long expiresAt) {
    LeaseData lease = new LeaseData();
    lease.setHolderId(holderId);
    lease.setRequestUri(uri);
    lease.setLockPath(storagePath);
    lease.setLeaseDurationMs(30_000L);
    lease.setExpiresAt(expiresAt);
    lease.setAcquiredAt(expiresAt - 30_000L);
    return lease;
  }
}
