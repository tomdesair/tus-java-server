package me.desair.tus.server.upload.disk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link LeaseFileUploadLock} verifying heartbeat renewal, lifecycle cleanup, and
 * stream unregistration.
 */
public class LeaseFileUploadLockTest {

  private static Path storagePath;
  private Path testLockDir;
  private Path testStopFile;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "lease-lock-handle-test").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Before
  public void setUp() throws IOException {
    testLockDir = storagePath.resolve("upload-" + UUID.randomUUID() + ".lock");
    testStopFile = storagePath.resolve("upload-" + UUID.randomUUID() + ".stop");
    Files.createDirectories(testLockDir);
  }

  @After
  public void tearDown() throws IOException {
    if (Files.exists(testLockDir)) {
      FileUtils.deleteDirectory(testLockDir.toFile());
    }
    Files.deleteIfExists(testStopFile);
    Path mutexDir = LeaseFileMutex.resolveMutexDir(testLockDir);
    if (mutexDir != null && Files.exists(mutexDir)) {
      FileUtils.deleteDirectory(mutexDir.toFile());
    }
  }

  private LeaseData createLeaseData(String holderId, String requestUri, long durationMs) {
    return new LeaseData(
        holderId,
        requestUri,
        durationMs,
        System.currentTimeMillis() + durationMs,
        System.currentTimeMillis(),
        testLockDir != null ? testLockDir.toString() : null,
        testStopFile != null ? testStopFile.toString() : null);
  }

  @Test
  public void testHeartbeatRenewalUpdatesExpiresAt() throws Exception {
    String holderId = "holder-" + UUID.randomUUID();
    String requestUri = "/files/upload/test-123";
    long leaseDurationMs = 30_000L;
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    LeaseData leaseData = createLeaseData(holderId, requestUri, leaseDurationMs);
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(leaseData, testLockDir, testStopFile, activeStreams);

    long initialExpiresAt = lock.getExpiresAt();
    Thread.sleep(50L);

    // Trigger explicit lease renewal
    lock.renewLease();

    assertThat(lock.getExpiresAt(), greaterThan(initialExpiresAt));

    // Verify lease.json file on disk was updated
    Path leaseFile = testLockDir.resolve("lease.json");
    assertTrue(Files.exists(leaseFile));

    LeaseData deserialized = LeaseDataJsonSerializer.deserialize(leaseFile);
    assertThat(deserialized, is(notNullValue()));
    assertThat(deserialized.getExpiresAt(), is(lock.getExpiresAt()));
    assertThat(deserialized.getHolderId(), is(holderId));

    lock.close();
  }

  @Test
  public void testCloseTerminatesHeartbeatAndDeletesLockDir() throws Exception {
    String holderId = "holder-" + UUID.randomUUID();
    String requestUri = "/files/upload/test-456";
    long leaseDurationMs = 30_000L;
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();
    activeStreams.put(requestUri, new ByteArrayInputStream("data".getBytes()));

    // Create stop file to verify it gets cleaned up on close
    Files.write(testStopFile, new byte[0]);
    assertTrue(Files.exists(testStopFile));

    LeaseData leaseData = createLeaseData(holderId, requestUri, leaseDurationMs);
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(leaseData, testLockDir, testStopFile, activeStreams);

    // Write initial lease file
    lock.renewLease();
    assertTrue(Files.exists(testLockDir.resolve("lease.json")));

    // Close the lock
    lock.close();

    // Verify lock directory and lease file were deleted
    assertFalse(Files.exists(testLockDir.resolve("lease.json")));
    assertFalse(Files.exists(testLockDir));

    // Verify stop file was deleted
    assertFalse(Files.exists(testStopFile));

    // Verify active stream was unregistered
    assertFalse(activeStreams.containsKey(requestUri));
  }

  @Test
  public void testReleaseDelegatesToClose() throws Exception {
    String holderId = "holder-" + UUID.randomUUID();
    String requestUri = "/files/upload/test-release";
    long leaseDurationMs = 30_000L;
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    LeaseData leaseData = createLeaseData(holderId, requestUri, leaseDurationMs);
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(leaseData, testLockDir, testStopFile, activeStreams);

    assertThat(lock.getUploadUri(), is(requestUri));

    lock.release();

    assertFalse(Files.exists(testLockDir));
  }

  @Test
  public void testLeaseDataGettersAndSetters() {
    LeaseData data = new LeaseData();

    data.setHolderId("test-holder");
    data.setRequestUri("/files/upload/uri");
    data.setLockPath("/var/storage/locks/1.lock");
    data.setStopPath("/var/storage/locks/1.stop");
    data.setLeaseDurationMs(15000L);
    data.setExpiresAt(200000L);
    data.setAcquiredAt(100000L);

    assertThat(data.getHolderId(), is("test-holder"));
    assertThat(data.getRequestUri(), is("/files/upload/uri"));
    assertThat(data.getLockPath(), is("/var/storage/locks/1.lock"));
    assertThat(data.getStopPath(), is("/var/storage/locks/1.stop"));
    assertThat(data.getLeaseDurationMs(), is(15000L));
    assertThat(data.getExpiresAt(), is(200000L));
    assertThat(data.getAcquiredAt(), is(100000L));
    assertTrue(data.isExpired(300000L));
    assertFalse(data.isExpired(100000L));
  }

  @Test
  public void testActiveLockConstructorInitializesFields() {
    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, testLockDir, testStopFile, null);

    assertThat(lock.getHolderId(), is("holder"));
    assertThat(lock.getRequestUri(), is("/uri"));
    assertThat(lock.getStoragePath(), is(testLockDir.toString()));
    assertThat(lock.getLeaseDurationMs(), is(10000L));
    assertThat(lock.getExpiresAt(), greaterThan(0L));
    assertThat(lock.getAcquiredAt(), greaterThan(0L));
    assertThat(lock.getLeaseData(), is(leaseData));

    lock.close();
  }

  @Test
  public void testConstructorWithZeroLeaseDurationDoesNotScheduleWatchdog() {
    LeaseData leaseData = createLeaseData("holder", "/uri", 0L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, testLockDir, testStopFile, null);
    assertEquals("holder", lock.getHolderId());
    lock.close();
  }

  @Test
  public void testRenewLeaseWithNullLockDirShouldBeNoOp() {
    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, null, null, null);
    // Should safely do nothing without throwing exception
    lock.renewLease();
    assertEquals("holder", lock.getHolderId());
    lock.close();
  }

  @Test
  public void testRenewLeaseWhenLockDirIsDeletedOrInvalid() {
    Path nonExistentDir = storagePath.resolve("non-existent-lock-dir-" + UUID.randomUUID());
    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, nonExistentDir, null, null);
    // When directory doesn't exist, renewLease logs a warning and does not throw
    lock.renewLease();
    assertEquals("holder", lock.getHolderId());
    lock.close();
  }

  @Test
  public void testCloseWhenLockDirIsAlreadyDeleted() throws Exception {
    Path dir = storagePath.resolve("already-deleted-" + UUID.randomUUID());
    Files.createDirectories(dir);
    FileUtils.deleteDirectory(dir.toFile());

    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, dir, null, null);
    // Should execute cleanly without error
    lock.close();
    assertFalse(Files.exists(dir));
  }

  @Test
  public void testCloseWithActiveStreamsAndStopFile() throws Exception {
    Path dir = storagePath.resolve("active-close-" + UUID.randomUUID());
    Files.createDirectories(dir);
    Path stopFile = storagePath.resolve("stop-file-" + UUID.randomUUID());
    Files.write(stopFile, new byte[0]);

    Map<String, InputStream> streams = new ConcurrentHashMap<>();
    streams.put("/active/uri", new ByteArrayInputStream("test".getBytes()));

    LeaseData leaseData = createLeaseData("holder", "/active/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, dir, stopFile, streams);
    assertTrue(Files.exists(stopFile));

    lock.close();

    assertFalse(Files.exists(stopFile));
    assertFalse(streams.containsKey("/active/uri"));
  }

  @Test
  public void testCloseWithNullRequestUriOrNullStreams() throws Exception {
    Path dir = storagePath.resolve("null-uri-" + UUID.randomUUID());
    Files.createDirectories(dir);

    Map<String, InputStream> streams = new ConcurrentHashMap<>();
    LeaseData leaseDataNullUri = createLeaseData("holder", null, 10000L);
    LeaseFileUploadLock lockWithNullUri =
        new LeaseFileUploadLock(leaseDataNullUri, dir, null, streams);
    lockWithNullUri.close();
    assertNotNull(lockWithNullUri.getHolderId());

    Path dir2 = storagePath.resolve("null-streams-" + UUID.randomUUID());
    Files.createDirectories(dir2);
    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lockWithNullStreams = new LeaseFileUploadLock(leaseData, dir2, null, null);
    lockWithNullStreams.close();
    assertNotNull(lockWithNullStreams.getHolderId());
  }

  @Test
  public void testCloseWhenLockDirCannotBeDeleted() throws Exception {
    Path dir = storagePath.resolve("non-empty-" + UUID.randomUUID());
    Files.createDirectories(dir);
    // Write an extra file inside the lock dir so Files.deleteIfExists(lockDirPath) throws
    // DirectoryNotEmptyException
    Files.write(dir.resolve("extra-file.txt"), "data".getBytes());

    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, dir, null, null);
    // Should catch DirectoryNotEmptyException, log warning, and complete cleanly
    lock.close();
    assertTrue(Files.exists(dir));

    FileUtils.deleteDirectory(dir.toFile());
  }

  @Test
  public void testRenewLeaseWhenLockDirPathIsRegularFile() throws Exception {
    Path fileAsDir = storagePath.resolve("file-as-dir-" + UUID.randomUUID());
    Files.write(fileAsDir, "not a directory".getBytes());

    LeaseData leaseData = createLeaseData("holder", "/uri", 10000L);
    LeaseFileUploadLock lock = new LeaseFileUploadLock(leaseData, fileAsDir, null, null);
    // Should catch exception attempting to create file inside a regular file and log warning
    lock.renewLease();
    assertTrue(Files.exists(fileAsDir));

    Files.deleteIfExists(fileAsDir);
  }

  @Test
  public void testCloseDoesNotDeleteSuccessorLockWhenHolderIdMismatch() throws Exception {
    Path dir = storagePath.resolve("successor-test-" + UUID.randomUUID() + ".lock");
    Files.createDirectories(dir);

    // Simulate that a successor node took over the lock with a new holderId
    LeaseData successorLease =
        new LeaseData(
            "successor-holder",
            "/files/upload/test",
            30_000L,
            System.currentTimeMillis() + 30_000L,
            System.currentTimeMillis(),
            dir.toString(),
            null);
    LeaseDataJsonSerializer.serializeToPath(successorLease, dir.resolve("lease.json"));

    // Original lock holder (who unpaused or awoke late) calls close()
    LeaseData originalLease =
        new LeaseData(
            "original-holder",
            "/files/upload/test",
            30_000L,
            System.currentTimeMillis() - 1000L,
            System.currentTimeMillis() - 31_000L,
            dir.toString(),
            null);
    LeaseFileUploadLock originalLock = new LeaseFileUploadLock(originalLease, dir, null, null);

    originalLock.close();

    // The successor's lock directory and lease file must NOT be deleted
    assertTrue(Files.exists(dir));
    assertTrue(Files.exists(dir.resolve("lease.json")));

    LeaseData preservedLease = LeaseDataJsonSerializer.deserialize(dir.resolve("lease.json"));
    assertNotNull(preservedLease);
    assertEquals("successor-holder", preservedLease.getHolderId());

    FileUtils.deleteDirectory(dir.toFile());
  }

  @Test
  public void testRenewLeaseAbortsWhenHolderIdMismatch() throws Exception {
    Path dir = storagePath.resolve("successor-renew-test-" + UUID.randomUUID() + ".lock");
    Files.createDirectories(dir);

    // Simulate successor node took over with a new holderId
    long successorExpiry = System.currentTimeMillis() + 60_000L;
    LeaseData successorLease =
        new LeaseData(
            "successor-holder",
            "/files/upload/test",
            30_000L,
            successorExpiry,
            System.currentTimeMillis(),
            dir.toString(),
            null);
    LeaseDataJsonSerializer.serializeToPath(successorLease, dir.resolve("lease.json"));

    // Stale original lock holder calls renewLease()
    LeaseData originalLease =
        new LeaseData(
            "original-holder",
            "/files/upload/test",
            30_000L,
            System.currentTimeMillis() + 10_000L,
            System.currentTimeMillis(),
            dir.toString(),
            null);
    LeaseFileUploadLock originalLock = new LeaseFileUploadLock(originalLease, dir, null, null);

    originalLock.renewLease();

    // Successor lease must remain untouched with successor's holderId
    LeaseData currentLease = LeaseDataJsonSerializer.deserialize(dir.resolve("lease.json"));
    assertNotNull(currentLease);
    assertEquals("successor-holder", currentLease.getHolderId());
    assertEquals(successorExpiry, currentLease.getExpiresAt());

    originalLock.close();
    FileUtils.deleteDirectory(dir.toFile());
  }
}
