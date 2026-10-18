package me.desair.tus.server.upload.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link DiskChecksumIndex}. */
public class DiskChecksumIndexTest {

  private Path tempDir;
  private DiskChecksumIndex index;
  private final ChecksumKey checksumKey =
      ChecksumKey.of("0123456789abcdef0123456789abcdef01234567", ChecksumAlgorithm.SHA1);

  @Before
  public void setUp() throws Exception {
    tempDir = Files.createTempDirectory("tus-disk-checksum-test-");
    index = new DiskChecksumIndex(tempDir.toString());
  }

  @After
  public void tearDown() throws Exception {
    FileUtils.deleteDirectory(tempDir.toFile());
  }

  @Test
  public void testTryRegisterParentSuccessAndFindParent() throws IOException {
    UploadId uploadId = new UploadId("parent-123");
    assertTrue(index.tryRegisterParent(checksumKey, uploadId));

    assertEquals(uploadId, index.findParent(checksumKey));
    assertFalse(index.isParentDeleted(checksumKey));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testTryRegisterParentConflictFails() throws IOException {
    UploadId uploadId1 = new UploadId("parent-1");
    UploadId uploadId2 = new UploadId("parent-2");

    assertTrue(index.tryRegisterParent(checksumKey, uploadId1));
    assertFalse(index.tryRegisterParent(checksumKey, uploadId2));

    assertEquals(uploadId1, index.findParent(checksumKey));
  }

  @Test
  public void testMarkParentDeletedAndTombstone() throws IOException {
    UploadId uploadId = new UploadId("parent-123");
    index.tryRegisterParent(checksumKey, uploadId);

    index.markParentDeleted(checksumKey);

    assertTrue(index.isParentDeleted(checksumKey));
    // Parent marker is preserved during tombstoning so children can resolve parent ID
    assertEquals(uploadId, index.findParent(checksumKey));

    // After tombstone, another upload cannot register as parent
    assertFalse(index.tryRegisterParent(checksumKey, new UploadId("parent-456")));
  }

  @Test
  public void testAddAndRemoveChildren() throws IOException {
    UploadId child1 = new UploadId("child-1");
    UploadId child2 = new UploadId("child-2");

    assertFalse(index.hasChildren(checksumKey));

    index.addChild(checksumKey, child1);
    assertTrue(index.hasChildren(checksumKey));

    index.addChild(checksumKey, child2);
    assertTrue(index.hasChildren(checksumKey));

    index.removeChild(checksumKey, child1);
    assertTrue(index.hasChildren(checksumKey));

    index.removeChild(checksumKey, child2);
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testDeleteIndexRemovesDirectory() throws IOException {
    UploadId parent = new UploadId("parent");
    UploadId child = new UploadId("child");

    index.tryRegisterParent(checksumKey, parent);
    index.addChild(checksumKey, child);

    index.deleteIndex(checksumKey);

    assertNull(index.findParent(checksumKey));
    assertFalse(index.isParentDeleted(checksumKey));
    assertFalse(index.hasChildren(checksumKey));
  }

  @Test
  public void testGetAllIndexedKeysEmptyAndNonExistent() throws IOException {
    assertTrue(index.getAllIndexedKeys().isEmpty());

    DiskChecksumIndex nonExistentIndex =
        new DiskChecksumIndex(tempDir.resolve("does-not-exist").toString());
    assertTrue(nonExistentIndex.getAllIndexedKeys().isEmpty());
  }

  @Test
  public void testGetAllIndexedKeysWithValidAndInvalidEntries() throws IOException {
    UploadId parentId = new UploadId("parent-123");
    index.tryRegisterParent(checksumKey, parentId);

    Path checksumsDir = tempDir.resolve("checksums");

    // Create a regular file directly inside storagePath (must be skipped)
    Files.createFile(checksumsDir.resolve("regular-file.tmp"));

    // Create an invalid/unknown algorithm directory (must be skipped)
    Files.createDirectories(checksumsDir.resolve("unknown-algo").resolve("some-hash"));

    // Create a valid algorithm directory with a hash dir that lacks a parent file (must be skipped)
    Files.createDirectories(checksumsDir.resolve("sha1").resolve("empty-hash-dir"));

    // Create a valid algorithm directory with an empty file instead of a directory (must be
    // skipped)
    Files.createFile(checksumsDir.resolve("sha1").resolve("not-a-dir"));

    // Create a malformed hash directory (must be caught and skipped)
    Path malformedDir = checksumsDir.resolve("sha1").resolve("invalid_hex_chars_!@#$");
    Files.createDirectories(malformedDir);
    Files.createFile(malformedDir.resolve("parent"));

    Set<ChecksumKey> keys = index.getAllIndexedKeys();
    assertEquals(1, keys.size());
    assertTrue(keys.contains(checksumKey));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testChildIdPathTraversalThrowsException() throws IOException {
    index.addChild(checksumKey, new UploadId("../../..%2F/etc/passwd"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testRemoveChildIdPathTraversalThrowsException() throws IOException {
    index.removeChild(checksumKey, new UploadId("../../..%2F/etc/passwd"));
  }
}
