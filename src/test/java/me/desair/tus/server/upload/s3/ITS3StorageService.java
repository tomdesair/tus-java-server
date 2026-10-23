package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITS3StorageService {

  private static GenericContainer<?> rustfsContainer;
  private static MinioClient minioClient;
  private static final String BUCKET = "test-storage-service-bucket";

  private S3StorageService storageService;

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers S3 (RustFS) test",
        TestUtils.isContainerRuntimeAvailable());

    rustfsContainer = TestUtils.createRustFsContainer();
    rustfsContainer.start();

    minioClient = TestUtils.createMinioClient(rustfsContainer);
    TestUtils.createBucket(minioClient, BUCKET);
  }

  @AfterClass
  public static void tearDownClass() {
    if (rustfsContainer != null) {
      rustfsContainer.stop();
    }
  }

  @Before
  public void setUp() {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    String endpoint = TestUtils.getS3Endpoint(rustfsContainer);
    storageService = new S3StorageService(endpoint, "rustfsadmin", "rustfsadmin", BUCKET);
  }

  @Test
  public void testFullUploadLifecycle() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(11L);

    info = storageService.create(info, "owner1");
    assertNotNull(info.getId());
    assertNotNull(info.getStorageUploadId());
    assertEquals("owner1", info.getOwnerKey());

    // Append data
    ByteArrayInputStream bais =
        new ByteArrayInputStream("hello world".getBytes(StandardCharsets.UTF_8));
    info = storageService.append(info, bais);
    assertEquals(Long.valueOf(11), info.getOffset());

    // Verify uploaded bytes
    try (InputStream is = storageService.getUploadedBytes(info.getId())) {
      assertNotNull(is);
      assertEquals("hello world", IOUtils.toString(is, StandardCharsets.UTF_8));
    }

    // Verify getUploadInfo by URI and ID
    UploadInfo fetched = storageService.getUploadInfo(info.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(11), fetched.getOffset());

    // Terminate upload
    storageService.terminateUpload(info);
    assertNull(storageService.getUploadInfo(info.getId()));
  }

  @Test
  public void testDeduplicationOnS3() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    byte[] content = "S3 Deduplicated Content".getBytes(StandardCharsets.UTF_8);
    byte[] sha1Digest = DigestUtils.sha1(content);
    String sha1Base64 = org.apache.commons.codec.binary.Base64.encodeBase64String(sha1Digest);
    String sha1Hex = org.apache.commons.codec.binary.Hex.encodeHexString(sha1Digest);
    String parentObjectKey = "checksums/sha1/" + sha1Hex + "/parent";
    String parentDeletedKey = "checksums/sha1/" + sha1Hex + "/parent-deleted";

    // 1. Parent upload
    UploadInfo parent = new UploadInfo();
    parent.setLength((long) content.length);
    parent.setChecksum(sha1Base64);
    parent.setChecksumAlgorithm(ChecksumAlgorithm.SHA1);
    parent = storageService.create(parent, "owner1");

    storageService.append(parent, new ByteArrayInputStream(content));

    // Verify parent pointer exists in S3
    assertNotNull(
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET).object(parentObjectKey).build()));

    // Look up by checksum
    UploadInfo found = storageService.getUploadInfoByChecksum(sha1Base64, ChecksumAlgorithm.SHA1);
    assertNotNull(found);
    assertEquals(parent.getId(), found.getId());

    // 2. Child duplicate upload
    UploadInfo child = new UploadInfo();
    child.setLength((long) content.length);
    child.setChecksum(sha1Base64);
    child.setChecksumAlgorithm(ChecksumAlgorithm.SHA1);
    child = storageService.create(child, "owner1");
    child = storageService.append(child, new ByteArrayInputStream(content));

    assertEquals(parent.getId(), child.getDuplicatesUploadId());
    String childObjectKey = "checksums/sha1/" + sha1Hex + "/children/" + child.getId();
    assertNotNull(
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET).object(childObjectKey).build()));

    // 3. Terminate parent upload (child still alive)
    storageService.terminateUpload(parent);

    assertNotNull(
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET).object(parentDeletedKey).build()));

    // 4. Terminate child upload (last child out)
    storageService.terminateUpload(child);

    // Verify all objects under checksum prefix are completely purged from S3
    Iterable<Result<Item>> remaining =
        minioClient.listObjects(
            ListObjectsArgs.builder().bucket(BUCKET).prefix("checksums/sha1/" + sha1Hex).build());
    assertFalse(remaining.iterator().hasNext());
  }

  @Test
  public void testMultipartChunkedUploadAndFinalizationOnS3() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(20L);
    info = storageService.create(info, "owner-multipart");

    // Part 1: append 10 bytes (creates an incomplete .part file)
    byte[] part1 = "1234567890".getBytes(StandardCharsets.UTF_8);
    info = storageService.append(info, new ByteArrayInputStream(part1));
    assertEquals(Long.valueOf(10L), info.getOffset());

    // Part 2: append 10 bytes (completes the upload, triggers leftover .part finalization on real
    // S3)
    byte[] part2 = "abcdefghij".getBytes(StandardCharsets.UTF_8);
    info = storageService.append(info, new ByteArrayInputStream(part2));
    assertEquals(Long.valueOf(20L), info.getOffset());

    // Verify uploaded bytes on real S3
    try (InputStream is = storageService.getUploadedBytes(info.getId())) {
      assertNotNull(is);
      assertEquals("1234567890abcdefghij", IOUtils.toString(is, StandardCharsets.UTF_8));
    }
  }

  @Test
  public void testTruncationOnRealS3() throws Exception {
    // Scenario A: Truncate in-progress upload with .part object
    UploadInfo info1 = new UploadInfo();
    info1.setLength(50L);
    info1 = storageService.create(info1, "owner-trunc1");

    byte[] bytes1 =
        "Hello, World! This is an in-progress payload.".getBytes(StandardCharsets.UTF_8);
    info1 = storageService.append(info1, new ByteArrayInputStream(bytes1));
    assertEquals(Long.valueOf(bytes1.length), info1.getOffset());

    storageService.removeLastNumberOfBytes(info1, 10L);
    assertEquals(Long.valueOf(bytes1.length - 10), info1.getOffset());

    // Scenario B: Truncate completed upload
    UploadInfo info2 = new UploadInfo();
    info2.setLength(12L);
    info2 = storageService.create(info2, "owner-trunc2");
    info2 =
        storageService.append(
            info2, new ByteArrayInputStream("Hello World!".getBytes(StandardCharsets.UTF_8)));
    assertEquals(Long.valueOf(12L), info2.getOffset());

    storageService.removeLastNumberOfBytes(info2, 6L);
    assertEquals(Long.valueOf(6L), info2.getOffset());
  }

  @Test
  public void testCleanupExpiredUploadsOnS3() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    info = storageService.create(info, "owner-exp");

    // Set expiration in the past
    info.setExpirationTimestamp(System.currentTimeMillis() - 60000L);
    storageService.update(info);

    assertNotNull(storageService.getUploadInfo(info.getId()));

    // Run cleanup on real S3
    storageService.cleanupExpiredUploads(null);

    // Verify upload was cleaned up from real S3
    assertNull(storageService.getUploadInfo(info.getId()));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesMissingData() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);
    String sha1Hex = "0123456789abcdef0123456789abcdef01234567";
    String parentKey = "checksums/sha1/" + sha1Hex + "/parent";
    byte[] parentContent = "orphan-parent-id".getBytes(StandardCharsets.UTF_8);

    // Create an orphaned checksum index pointing to a non-existent data object in S3
    putObject(parentKey, parentContent);
    assertTrue(objectExists(parentKey));

    // Running cleanupExpiredUploads should detect the missing parent data and prune the index
    storageService.cleanupExpiredUploads();

    // Verify the orphaned parent checksum pointer is deleted from S3
    assertFalse(objectExists(parentKey));
  }

  @Test
  public void testPruneOrphanedChecksumIndicesAbandonedTombstone() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);
    String sha1Hex = "abcdef0123456789abcdef0123456789abcdef01";
    String parentKey = "checksums/sha1/" + sha1Hex + "/parent";
    String parentDeletedKey = "checksums/sha1/" + sha1Hex + "/parent-deleted";
    byte[] parentContent = "tombstone-parent-id".getBytes(StandardCharsets.UTF_8);

    // Create parent and parent-deleted tombstone marker with zero children in S3
    putObject(parentKey, parentContent);
    putObject(parentDeletedKey, new byte[0]);

    assertTrue(objectExists(parentKey));
    assertTrue(objectExists(parentDeletedKey));

    // Running cleanupExpiredUploads should detect the abandoned tombstone and prune both markers
    storageService.cleanupExpiredUploads();

    // Verify both the parent pointer and tombstone marker are pruned from S3
    assertFalse(objectExists(parentKey));
    assertFalse(objectExists(parentDeletedKey));
  }

  @Test
  public void testS3ChecksumIndexDirectly() throws Exception {
    S3ChecksumIndex index = new S3ChecksumIndex(minioClient, BUCKET, "test-checksums");
    ChecksumKey key =
        ChecksumKey.of("1122334455667788990011223344556677889900", ChecksumAlgorithm.SHA1);
    UploadId parentId = new UploadId("parent-upload-id");
    UploadId competingId = new UploadId("competing-upload-id");

    // 1. Initial lookup returns null
    assertNull(index.findParent(key));
    assertFalse(index.isParentDeleted(key));
    assertFalse(index.hasChildren(key));

    // 2. First registration succeeds
    assertTrue(index.tryRegisterParent(key, parentId));
    assertEquals(parentId, index.findParent(key));

    // 3. Second registration fails due to CAS (precondition failed) or duplicate parent
    assertFalse(index.tryRegisterParent(key, competingId));
    assertEquals(parentId, index.findParent(key));

    // 4. Add and remove child
    UploadId childId = new UploadId("child-upload-id");
    index.addChild(key, childId);
    assertTrue(index.hasChildren(key));
    index.removeChild(key, childId);
    assertFalse(index.hasChildren(key));

    // 5. Mark parent deleted (preserves parent file for active children and new joiners)
    index.markParentDeleted(key);
    assertTrue(index.isParentDeleted(key));
    assertEquals(parentId, index.findParent(key));

    // 6. Registration after tombstone returns false
    assertFalse(index.tryRegisterParent(key, competingId));

    // 7. Empty parent object returns null
    ChecksumKey emptyKey =
        ChecksumKey.of("2233445566778899001122334455667788990011", ChecksumAlgorithm.SHA1);
    putObject("test-checksums/sha1/2233445566778899001122334455667788990011/parent", new byte[0]);
    assertNull(index.findParent(emptyKey));

    // 8. Delete entire index
    index.deleteIndex(key);
    assertNull(index.findParent(key));
  }

  private void putObject(String objectKey, byte[] data) throws Exception {
    minioClient.putObject(
        PutObjectArgs.builder().bucket(BUCKET).object(objectKey).stream(
                new ByteArrayInputStream(data), (long) data.length, -1L)
            .build());
  }

  private boolean objectExists(String objectKey) {
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}
