package me.desair.tus.server.upload.disk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
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
import me.desair.tus.server.util.Utils;
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
  }

  @Test
  public void testHeartbeatRenewalUpdatesExpiresAt() throws Exception {
    String holderId = "holder-" + UUID.randomUUID();
    String requestUri = "/files/upload/test-123";
    long leaseDurationMs = 30_000L;
    Map<String, InputStream> activeStreams = new ConcurrentHashMap<>();

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(
            testLockDir, testStopFile, holderId, leaseDurationMs, requestUri, activeStreams);

    long initialExpiresAt = lock.getExpiresAt();
    Thread.sleep(50L);

    // Trigger explicit lease renewal
    lock.renewLease();

    assertThat(lock.getExpiresAt(), greaterThan(initialExpiresAt));

    // Verify lease.json file on disk was updated
    Path leaseFile = testLockDir.resolve("lease.json");
    assertTrue(Files.exists(leaseFile));

    LeaseFileUploadLock deserialized = Utils.readJson(leaseFile, LeaseFileUploadLock.class, false);
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

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(
            testLockDir, testStopFile, holderId, leaseDurationMs, requestUri, activeStreams);

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

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(
            testLockDir, testStopFile, holderId, leaseDurationMs, requestUri, activeStreams);

    assertThat(lock.getUploadUri(), is(requestUri));

    lock.release();

    assertFalse(Files.exists(testLockDir));
  }

  @Test
  public void testGettersAndSettersAndDefaultConstructor() {
    LeaseFileUploadLock lock = new LeaseFileUploadLock();

    lock.setHolderId("test-holder");
    lock.setRequestUri("/files/upload/uri");
    lock.setStoragePath("/var/storage");
    lock.setLeaseDurationMs(15000L);
    lock.setExpiresAt(200000L);
    lock.setAcquiredAt(100000L);

    assertThat(lock.getHolderId(), is("test-holder"));
    assertThat(lock.getRequestUri(), is("/files/upload/uri"));
    assertThat(lock.getStoragePath(), is("/var/storage"));
    assertThat(lock.getLeaseDurationMs(), is(15000L));
    assertThat(lock.getExpiresAt(), is(200000L));
    assertThat(lock.getAcquiredAt(), is(100000L));
    assertThat(lock.getUploadUri(), is("/files/upload/uri"));
  }

  @Test
  public void testCustomConstructorForSerialization() {
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock("holder", "/uri", "/storage", 10000L, 50000L);

    assertThat(lock.getHolderId(), is("holder"));
    assertThat(lock.getRequestUri(), is("/uri"));
    assertThat(lock.getStoragePath(), is("/storage"));
    assertThat(lock.getLeaseDurationMs(), is(10000L));
    assertThat(lock.getExpiresAt(), is(50000L));
    assertThat(lock.getAcquiredAt(), greaterThan(0L));
  }

  @Test
  public void testRenewLeaseWithNullLockDirShouldBeNoOp() {
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(null, null, "holder", 10000L, "/uri", null, null);
    // Should safely do nothing without throwing exception
    lock.renewLease();
    lock.close();
  }

  @Test
  public void testRenewLeaseWhenLockDirIsDeletedOrInvalid() throws Exception {
    Path nonExistentDir = storagePath.resolve("non-existent-lock-dir-" + UUID.randomUUID());
    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(nonExistentDir, null, "holder", 10000L, "/uri", null, null);
    // When directory doesn't exist, renewLease logs a warning and does not throw
    lock.renewLease();
    lock.close();
  }

  @Test
  public void testCloseWhenLockDirIsAlreadyDeleted() throws Exception {
    Path dir = storagePath.resolve("already-deleted-" + UUID.randomUUID());
    Files.createDirectories(dir);
    FileUtils.deleteDirectory(dir.toFile());

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(dir, null, "holder", 10000L, "/uri", null, null);
    // Should execute cleanly without error
    lock.close();
  }

  @Test
  public void testCloseWithActiveStreamsAndStopFile() throws Exception {
    Path dir = storagePath.resolve("active-close-" + UUID.randomUUID());
    Files.createDirectories(dir);
    Path stopFile = storagePath.resolve("stop-file-" + UUID.randomUUID());
    Files.write(stopFile, new byte[0]);

    Map<String, InputStream> streams = new ConcurrentHashMap<>();
    streams.put("/active/uri", new ByteArrayInputStream("test".getBytes()));

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(dir, stopFile, "holder", 10000L, "/active/uri", streams, null);
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
    LeaseFileUploadLock lockWithNullUri =
        new LeaseFileUploadLock(dir, null, "holder", 10000L, null, streams, null);
    lockWithNullUri.close();

    Path dir2 = storagePath.resolve("null-streams-" + UUID.randomUUID());
    Files.createDirectories(dir2);
    LeaseFileUploadLock lockWithNullStreams =
        new LeaseFileUploadLock(dir2, null, "holder", 10000L, "/uri", null, null);
    lockWithNullStreams.close();
  }

  @Test
  public void testCloseWhenLockDirCannotBeDeleted() throws Exception {
    Path dir = storagePath.resolve("non-empty-" + UUID.randomUUID());
    Files.createDirectories(dir);
    // Write an extra file inside the lock dir so Files.deleteIfExists(lockDirPath) throws
    // DirectoryNotEmptyException
    Files.write(dir.resolve("extra-file.txt"), "data".getBytes());

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(dir, null, "holder", 10000L, "/uri", null, null);
    // Should catch DirectoryNotEmptyException, log warning, and complete cleanly
    lock.close();

    FileUtils.deleteDirectory(dir.toFile());
  }

  @Test
  public void testRenewLeaseWhenLockDirPathIsRegularFile() throws Exception {
    Path fileAsDir = storagePath.resolve("file-as-dir-" + UUID.randomUUID());
    Files.write(fileAsDir, "not a directory".getBytes());

    LeaseFileUploadLock lock =
        new LeaseFileUploadLock(fileAsDir, null, "holder", 10000L, "/uri", null, null);
    // Should catch exception attempting to create file inside a regular file and log warning
    lock.renewLease();

    Files.deleteIfExists(fileAsDir);
  }
}
