package me.desair.tus.server.upload.disk;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertFalse;
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
import java.util.UUID;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

@RunWith(MockitoJUnitRunner.Silent.class)
public class DiskLockingServiceTest {

  public static final String UPLOAD_URL = "/upload/test";
  private DiskLockingService lockingService;

  @Mock private UploadIdFactory idFactory;

  private static Path storagePath;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "data").toAbsolutePath();
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
                return new UploadId(
                    StringUtils.substringAfter(
                        invocation.getArguments()[0].toString(), UPLOAD_URL + "/"));
              }
            });

    lockingService = new DiskLockingService(idFactory, storagePath.toString());
  }

  @Test
  public void lockUploadByUri() throws Exception {
    UploadLock uploadLock =
        lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");

    assertThat(uploadLock, not(nullValue()));

    uploadLock.release();
  }

  @Test
  public void isLockedTrue() throws Exception {
    UploadLock uploadLock =
        lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");

    assertThat(
        lockingService.isLocked(new UploadId("000003f1-a850-49de-af03-997272d834c9")), is(true));

    uploadLock.release();
  }

  @Test
  public void isLockedFalse() throws Exception {
    UploadLock uploadLock =
        lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");
    uploadLock.release();

    assertThat(
        lockingService.isLocked(new UploadId("000003f1-a850-49de-af03-997272d834c9")), is(false));
  }

  @Test
  public void lockUploadNotExists() throws Exception {
    reset(idFactory);
    when(idFactory.readUploadId(nullable(String.class))).thenReturn(null);

    UploadLock uploadLock =
        lockingService.lockUploadByUri("/upload/test/000003f1-a850-49de-af03-997272d834c9");

    assertThat(uploadLock, nullValue());
  }

  @Test
  public void cleanupStaleLocks() throws Exception {
    Path locksPath = storagePath.resolve("locks");

    String activeLock = "000003f1-a850-49de-af03-997272d834c9";
    UploadLock uploadLock = lockingService.lockUploadByUri("/upload/test/" + activeLock);

    assertThat(uploadLock, not(nullValue()));

    String staleLock = UUID.randomUUID().toString();
    Files.createFile(locksPath.resolve(staleLock));

    String recentLock = UUID.randomUUID().toString();
    Files.createFile(locksPath.resolve(recentLock));

    Files.setLastModifiedTime(
        locksPath.resolve(staleLock), FileTime.fromMillis(System.currentTimeMillis() - 20000));
    Files.setLastModifiedTime(
        locksPath.resolve(activeLock), FileTime.fromMillis(System.currentTimeMillis() - 20000));

    assertTrue(Files.exists(locksPath.resolve(staleLock)));
    assertTrue(Files.exists(locksPath.resolve(activeLock)));
    assertTrue(Files.exists(locksPath.resolve(recentLock)));

    lockingService.cleanupStaleLocks();

    assertFalse(Files.exists(locksPath.resolve(staleLock)));
    assertTrue(Files.exists(locksPath.resolve(activeLock)));
    assertTrue(Files.exists(locksPath.resolve(recentLock)));

    uploadLock.release();
  }

  @Test
  public void testRegisterAndRequestLockReleaseLocal() throws Exception {
    String uri = "/upload/test/000003f1-a850-49de-af03-997272d834c9";
    byte[] data = new byte[] {1, 2, 3};
    ByteArrayInputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    lockingService.registerInputStream(uri, iis);
    assertFalse(iis.isInterrupted());

    lockingService.requestLockRelease(uri);
    assertTrue(iis.isInterrupted());

    // Stop file should also be created
    Path stopFilePath =
        storagePath.resolve("locks").resolve("000003f1-a850-49de-af03-997272d834c9.stop");
    assertTrue(Files.exists(stopFilePath));
    Files.deleteIfExists(stopFilePath);
  }

  @Test
  public void testWatchdogInterruptsStreamOnStopFile() throws Exception {
    String uri = "/upload/test/000003f1-a850-49de-af03-997272d834c9";
    byte[] data = new byte[] {1, 2, 3};
    ByteArrayInputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    lockingService.registerInputStream(uri, iis);
    assertFalse(iis.isInterrupted());

    // Manually create the stop file (simulating cross-replica signaling)
    Path stopFilePath =
        storagePath.resolve("locks").resolve("000003f1-a850-49de-af03-997272d834c9.stop");
    Files.createDirectories(stopFilePath.getParent());
    Files.write(stopFilePath, new byte[0]);

    // Wait for watchdog to poll (polls every 1000ms, wait up to 2.5s)
    long start = System.currentTimeMillis();
    while (!iis.isInterrupted() && System.currentTimeMillis() - start < 2500L) {
      Thread.sleep(100L);
    }

    assertTrue("Watchdog should have interrupted the stream", iis.isInterrupted());
    Files.deleteIfExists(stopFilePath);
  }

  @Test
  public void testWatchdogTerminatesWhenEmpty() throws Exception {
    String uri = "/upload/test/000003f1-a850-49de-af03-997272d834c9";
    byte[] data = new byte[] {1, 2, 3};
    ByteArrayInputStream bis = new ByteArrayInputStream(data);
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    lockingService.registerInputStream(uri, iis);

    // Watchdog should be running
    Thread watchdog = findWatchdogThread();
    assertTrue(watchdog != null && watchdog.isAlive());

    // Release/clear active locks (mimicking normal lock release/close)
    lockingService.requestLockRelease(uri);

    // Watchdog should stop (since loop exits after activeLocks is empty)
    long start = System.currentTimeMillis();
    while (watchdog.isAlive() && System.currentTimeMillis() - start < 2500L) {
      Thread.sleep(100L);
    }
    assertFalse("Watchdog thread should have terminated", watchdog.isAlive());

    // Clean up stop file
    Path stopFilePath =
        storagePath.resolve("locks").resolve("000003f1-a850-49de-af03-997272d834c9.stop");
    Files.deleteIfExists(stopFilePath);
  }

  private Thread findWatchdogThread() {
    ThreadGroup group = Thread.currentThread().getThreadGroup();
    while (group.getParent() != null) {
      group = group.getParent();
    }
    Thread[] threads = new Thread[group.activeCount() * 2];
    int count = group.enumerate(threads);
    for (int i = 0; i < count; i++) {
      if ("tus-lock-watchdog".equals(threads[i].getName())) {
        return threads[i];
      }
    }
    return null;
  }
}
