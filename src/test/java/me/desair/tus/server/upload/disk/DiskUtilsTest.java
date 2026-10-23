package me.desair.tus.server.upload.disk;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Serializable;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

public class DiskUtilsTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Path storagePath;

  @Before
  public void setUp() throws Exception {
    storagePath = temporaryFolder.newFolder("disk-utils-test").toPath();
  }

  @Test
  public void testResolveSafePath() {
    assertNull(DiskUtils.resolveSafePath(null, "child.txt", "error"));
    assertNull(DiskUtils.resolveSafePath(storagePath, null, "error"));

    Path resolved = DiskUtils.resolveSafePath(storagePath, "child.txt", "error");
    assertNotNull(resolved);
    assertEquals(storagePath.resolve("child.txt").normalize(), resolved.normalize());

    // Path traversal attempt directly with ..
    try {
      DiskUtils.resolveSafePath(storagePath, "../evil.txt", "Path traversal detected");
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("Path traversal detected", e.getMessage());
    }

    // Path traversal nested
    try {
      DiskUtils.resolveSafePath(storagePath, "sub/../../evil.txt", "Path traversal detected");
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("Path traversal detected", e.getMessage());
    }
  }

  @Test
  public void testInitStorageDirectory() throws Exception {
    try {
      DiskUtils.initStorageDirectory(null);
      fail("Expected NullPointerException on null storagePath");
    } catch (NullPointerException e) {
      // Expected
    }

    Path newDir = storagePath.resolve("new-storage-dir");
    assertFalse(Files.exists(newDir));
    DiskUtils.initStorageDirectory(newDir);
    assertTrue(Files.exists(newDir));

    // Calling again on existing directory should succeed cleanly
    DiskUtils.initStorageDirectory(newDir);
    assertTrue(Files.exists(newDir));

    // When an existing regular file blocks directory creation
    Path blockerFile = storagePath.resolve("blocker-file");
    Files.write(blockerFile, "content".getBytes());
    try {
      DiskUtils.initStorageDirectory(blockerFile.resolve("nested"));
      fail("Expected StoragePathNotAvailableException");
    } catch (StoragePathNotAvailableException e) {
      assertTrue(e.getMessage().contains("Unable to create the directory"));
    }
  }

  @Test
  public void testIsSafePathComponent() {
    assertFalse(DiskUtils.isSafePathComponent(null));
    assertFalse(DiskUtils.isSafePathComponent(""));
    assertFalse(DiskUtils.isSafePathComponent("   "));
    assertFalse(DiskUtils.isSafePathComponent(".."));
    assertFalse(DiskUtils.isSafePathComponent("a/b"));
    assertFalse(DiskUtils.isSafePathComponent("a\\b"));
    assertFalse(DiskUtils.isSafePathComponent("a:b"));
    assertFalse(DiskUtils.isSafePathComponent("a*b"));
    assertFalse(DiskUtils.isSafePathComponent("a?b"));
    assertFalse(DiskUtils.isSafePathComponent("a\"b"));
    assertFalse(DiskUtils.isSafePathComponent("a<b"));
    assertFalse(DiskUtils.isSafePathComponent("a>b"));
    assertFalse(DiskUtils.isSafePathComponent("a|b"));
    assertFalse(DiskUtils.isSafePathComponent("a\u0001b"));

    assertTrue(DiskUtils.isSafePathComponent("valid-file_name.123"));
    assertTrue(DiskUtils.isSafePathComponent("uploadId"));
  }

  @Test
  public void testNormalizeChecksum() {
    assertNull(DiskUtils.normalizeChecksum(null));

    // Hex string should remain unchanged
    String hex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    assertEquals(hex, DiskUtils.normalizeChecksum(hex));

    // Base64 encoded SHA-256 (44 chars ending in =)
    String base64 = "47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
    String expectedHex = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    assertEquals(expectedHex, DiskUtils.normalizeChecksum(base64));

    // Empty string
    assertEquals("", DiskUtils.normalizeChecksum(""));
  }

  @Test
  public void testIsPathOlderThan() throws Exception {
    assertFalse(DiskUtils.isPathOlderThan(null, 1000L));
    assertFalse(DiskUtils.isPathOlderThan(null, 1000L, 5000L));

    Path testFile = storagePath.resolve("age-test.tmp");
    Files.write(testFile, "data".getBytes());

    long now = System.currentTimeMillis();
    Files.setLastModifiedTime(testFile, FileTime.fromMillis(now - 5000L));

    assertTrue(DiskUtils.isPathOlderThan(testFile, 4000L, now));
    assertFalse(DiskUtils.isPathOlderThan(testFile, 6000L, now));

    assertTrue(DiskUtils.isPathOlderThan(testFile, 4000L));
    assertFalse(DiskUtils.isPathOlderThan(testFile, 100000L));

    Path nonExistent = storagePath.resolve("non-existent-" + UUID.randomUUID());
    assertFalse(DiskUtils.isPathOlderThan(nonExistent, 1000L));
  }

  @Test
  public void testDeleteDirectoryQuietly() throws Exception {
    assertFalse(DiskUtils.deleteDirectoryQuietly(null));
    Path nonExistent = storagePath.resolve("non-existent-" + UUID.randomUUID());
    assertFalse(DiskUtils.deleteDirectoryQuietly(nonExistent));

    Path testDir = storagePath.resolve("delete-dir-test-" + UUID.randomUUID());
    Path subDir = testDir.resolve("sub");
    Files.createDirectories(subDir);
    Files.write(subDir.resolve("test.txt"), "hello".getBytes());

    assertTrue(DiskUtils.deleteDirectoryQuietly(testDir));
    assertFalse(Files.exists(testDir));
  }

  @Test
  public void testDeleteDirectoryQuietlyIOException() throws Exception {
    FileSystemProvider mockProvider = mock(FileSystemProvider.class, Mockito.CALLS_REAL_METHODS);
    FileSystem mockFs = mock(FileSystem.class);
    when(mockFs.provider()).thenReturn(mockProvider);

    Path mockPath = mock(Path.class);
    when(mockPath.getFileSystem()).thenReturn(mockFs);

    Mockito.doNothing().when(mockProvider).checkAccess(mockPath);

    when(mockProvider.readAttributes(
            ArgumentMatchers.eq(mockPath),
            ArgumentMatchers.eq(BasicFileAttributes.class),
            ArgumentMatchers.any()))
        .thenThrow(new IOException("Simulated I/O failure"));

    assertFalse(DiskUtils.deleteDirectoryQuietly(mockPath));
  }

  @Test
  public void testCreateTempSiblingPath() {
    assertThat(DiskUtils.createTempSiblingPath(null), is(nullValue()));

    Path absPath = Paths.get("target", "test-dir", "data.json").toAbsolutePath();
    Path tempAbs = DiskUtils.createTempSiblingPath(absPath);
    assertThat(tempAbs, is(notNullValue()));
    assertThat(tempAbs.getParent(), is(absPath.getParent()));
    assertThat(tempAbs.getFileName().toString().startsWith("data.json.tmp."), is(true));

    Path relPathNoParent = Paths.get("data.json");
    Path tempRelNoParent = DiskUtils.createTempSiblingPath(relPathNoParent);
    assertThat(tempRelNoParent, is(notNullValue()));
    assertThat(tempRelNoParent.getFileName().toString().startsWith("data.json.tmp."), is(true));
  }

  @Test
  public void testTempPathAutoCloseable() throws Exception {
    Path targetFile = Paths.get("target", "temp-target-" + UUID.randomUUID() + ".json");
    Path tempFilePath;

    try (DiskUtils.TempPath tempPath = DiskUtils.createTempSibling(targetFile)) {
      tempFilePath = tempPath.getPath();
      assertThat(tempFilePath, is(notNullValue()));
      Files.write(tempFilePath, "temp data".getBytes());
      assertThat(Files.exists(tempFilePath), is(true));
    }

    // AutoCloseable should have automatically deleted the temp file
    assertThat(Files.exists(tempFilePath), is(false));

    // Null path handling
    try (DiskUtils.TempPath nullTempPath = new DiskUtils.TempPath(null)) {
      assertThat(nullTempPath.getPath(), is(nullValue()));
    }
  }

  @Test
  public void testAtomicMove() throws Exception {
    // Null inputs should not throw exception
    DiskUtils.atomicMove(null, Paths.get("dest"));
    DiskUtils.atomicMove(Paths.get("src"), null);

    Path src = Files.createTempFile("tus-disk-atomic-src", ".tmp");
    Path dst = Files.createTempFile("tus-disk-atomic-dst", ".tmp");
    try {
      Files.write(src, "atomic test content".getBytes());
      DiskUtils.atomicMove(src, dst);

      assertThat(Files.exists(src), is(false));
      assertThat(Files.exists(dst), is(true));
      assertThat(new String(Files.readAllBytes(dst)), is("atomic test content"));
    } finally {
      Files.deleteIfExists(src);
      Files.deleteIfExists(dst);
    }
  }

  @Test
  public void testReadAndWriteSerializable() throws Exception {
    // Null handling
    assertThat(DiskUtils.readSerializable(null, TestSerializable.class), is(nullValue()));
    DiskUtils.writeSerializable(null, storagePath.resolve("ignored.bin"));
    DiskUtils.writeSerializable(new TestSerializable("test"), null);

    // Normal serialization & deserialization
    TestSerializable original = new TestSerializable("test-value-123");
    Path file = storagePath.resolve("serializable.bin");
    DiskUtils.writeSerializable(original, file);

    TestSerializable result = DiskUtils.readSerializable(file, TestSerializable.class);
    assertThat(result, is(notNullValue()));
    assertThat(result.getValue(), is("test-value-123"));

    // Corrupted file
    Path corrupted = storagePath.resolve("corrupted.bin");
    Files.write(corrupted, "not a valid serialized object".getBytes());
    assertThat(DiskUtils.readSerializable(corrupted, TestSerializable.class), is(nullValue()));

    // Truncated file
    Path truncated = storagePath.resolve("truncated.bin");
    Files.write(truncated, new byte[] {(byte) 0xAC, (byte) 0xED, 0x00});
    assertThat(DiskUtils.readSerializable(truncated, TestSerializable.class), is(nullValue()));

    // Empty file
    Path empty = storagePath.resolve("empty.bin");
    Files.createFile(empty);
    assertThat(DiskUtils.readSerializable(empty, TestSerializable.class), is(nullValue()));
  }

  @Test
  public void testLockFileExclusivelyAndShared() throws Exception {
    Path tempFile = Files.createTempFile("tus-disk-lock", ".tmp");
    try (FileChannel channel =
        FileChannel.open(
            tempFile,
            java.nio.file.StandardOpenOption.READ,
            java.nio.file.StandardOpenOption.WRITE)) {
      FileLock lock1 = DiskUtils.lockFileExclusively(channel);
      assertThat(lock1, is(notNullValue()));
      lock1.release();

      FileLock lock2 = DiskUtils.lockFileShared(channel);
      assertThat(lock2, is(notNullValue()));
      lock2.release();
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test
  public void testInterruptThread() throws Exception {
    // Null should handle cleanly
    DiskUtils.interruptThread(null);

    // Alive thread
    AtomicBoolean wasInterrupted = new AtomicBoolean(false);
    Thread thread =
        new Thread(
            () -> {
              try {
                Thread.sleep(5000L);
              } catch (InterruptedException e) {
                wasInterrupted.set(true);
              }
            },
            "test-disk-thread");
    thread.start();
    Thread.sleep(50L);

    DiskUtils.interruptThread(thread);
    thread.join(2000L);
    assertTrue(wasInterrupted.get());

    // Unstarted thread
    Thread unstartedThread = new Thread(() -> {}, "test-disk-unstarted");
    DiskUtils.interruptThread(unstartedThread);
  }

  public static class TestSerializable implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String value;

    public TestSerializable(String value) {
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }
}
