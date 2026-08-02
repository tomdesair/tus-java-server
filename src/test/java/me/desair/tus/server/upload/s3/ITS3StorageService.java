package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.minio.MinioClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITS3StorageService {

  private static GenericContainer<?> minio;
  private static MinioClient minioClient;
  private static final String BUCKET = "test-storage-service-bucket";

  private S3StorageService storageService;

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers MinIO test",
        TestUtils.isContainerRuntimeAvailable());

    minio = TestUtils.createMinioContainer();
    minio.start();

    minioClient = TestUtils.createMinioClient(minio);
    TestUtils.createBucket(minioClient, BUCKET);
  }

  @AfterClass
  public static void tearDownClass() {
    if (minio != null) {
      minio.stop();
    }
  }

  @Before
  public void setUp() {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    storageService = new S3StorageService(minioClient, BUCKET);
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
    String sha1Base64 =
        org.apache.commons.codec.binary.Base64.encodeBase64String(DigestUtils.sha1(content));

    // Parent upload
    UploadInfo parent = new UploadInfo();
    parent.setLength((long) content.length);
    parent.setChecksum(sha1Base64);
    parent.setChecksumAlgorithm(ChecksumAlgorithm.SHA1);
    parent = storageService.create(parent, "owner1");

    storageService.append(parent, new ByteArrayInputStream(content));

    // Look up by checksum
    UploadInfo found = storageService.getUploadInfoByChecksum(sha1Base64, ChecksumAlgorithm.SHA1);
    assertNotNull(found);
    assertEquals(parent.getId(), found.getId());
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
}
