package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import me.desair.tus.server.upload.UploadId;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit tests for {@link LeaseFileMutex} verifying atomic acquisition, crash recovery, and cleanup.
 */
public class LeaseFileMutexTest {

  private static Path storagePath;

  @BeforeClass
  public static void setUpFolder() throws IOException {
    storagePath = Paths.get("target", "tus", "lease-mutex-test").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void tearDownFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Test
  public void testAcquireAndReleaseWithTryWithResources() throws Exception {
    Path lockDir = storagePath.resolve("test-" + UUID.randomUUID() + ".lock");

    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDir)) {
      assertNotNull(mutex.getPath());
      assertTrue(mutex.getPath().getFileName().toString().endsWith(".mutex"));
      assertTrue(mutex.isAcquired());
      assertTrue(Files.exists(mutex.getPath()));
    }

    Path mutexDir = LeaseFileMutex.resolveMutexDir(lockDir);
    assertNotNull(mutexDir);
    assertFalse(Files.exists(mutexDir));
  }

  @Test
  public void testConstructorsAndPathResolution() {
    assertNull(new LeaseFileMutex((Path) null).getPath());
    assertNull(new LeaseFileMutex(null, null).getPath());
    assertNull(new LeaseFileMutex(storagePath, null).getPath());
    assertNull(new LeaseFileMutex(null, new UploadId("123")).getPath());
    assertNull(LeaseFileMutex.resolveMutexDir(null));

    UploadId id = new UploadId("upload-abc");
    LeaseFileMutex mutexFromId = new LeaseFileMutex(storagePath, id);
    assertEquals(storagePath.resolve("upload-abc.mutex"), mutexFromId.getPath());
    assertTrue(mutexFromId.isAcquired());
    mutexFromId.release();

    Path lockPath = storagePath.resolve("upload-xyz.lock");
    LeaseFileMutex mutexFromLock = new LeaseFileMutex(lockPath);
    assertEquals(storagePath.resolve("upload-xyz.mutex"), mutexFromLock.getPath());
    assertTrue(mutexFromLock.isAcquired());
    mutexFromLock.release();

    Path explicitPath = storagePath.resolve("explicit.mutex");
    LeaseFileMutex explicitMutex = new LeaseFileMutex(explicitPath, true);
    assertEquals(explicitPath, explicitMutex.getPath());
    assertTrue(explicitMutex.isAcquired());
    explicitMutex.release();
  }

  @Test
  public void testDuplicateAcquireFailsAndUnacquiredCloseDoesNotDeleteExistingMutex()
      throws Exception {
    Path lockDir = storagePath.resolve("duplicate-" + UUID.randomUUID() + ".lock");
    try (LeaseFileMutex mutex1 = new LeaseFileMutex(lockDir)) {
      assertTrue(mutex1.isAcquired());
      assertTrue(Files.exists(mutex1.getPath()));

      // Second concurrent contender fails to acquire
      try (LeaseFileMutex mutex2 = new LeaseFileMutex(lockDir)) {
        assertFalse(mutex2.isAcquired());
      }

      // Closing unacquired mutex2 must NOT delete mutex1's active directory!
      assertTrue(Files.exists(mutex1.getPath()));
    }

    // After mutex1 is closed, directory is deleted and a new contender can acquire
    try (LeaseFileMutex mutex3 = new LeaseFileMutex(lockDir)) {
      assertTrue(mutex3.isAcquired());
      assertTrue(Files.exists(mutex3.getPath()));
    }
  }

  @Test
  public void testStaleMutexRecoveryAfterGracePeriod() throws Exception {
    Path lockDir = storagePath.resolve("stale-" + UUID.randomUUID() + ".lock");
    Path mutexDir = LeaseFileMutex.resolveMutexDir(lockDir);
    assertNotNull(mutexDir);

    Files.createDirectories(mutexDir);
    // Set mtime to 10 seconds ago (> 5s grace period)
    Files.setLastModifiedTime(mutexDir, FileTime.fromMillis(System.currentTimeMillis() - 10_000L));

    // Stale mutex from crashed node must be recovered and acquired in constructor
    try (LeaseFileMutex mutex = new LeaseFileMutex(lockDir)) {
      assertTrue(mutex.isAcquired());
      assertTrue(Files.exists(mutexDir));
    }

    assertFalse(Files.exists(mutexDir));
  }

  @Test
  public void testNullAndErrorHandling() {
    LeaseFileMutex nullMutex = new LeaseFileMutex((Path) null);
    assertFalse(nullMutex.isAcquired());
    nullMutex.release();
  }
}
