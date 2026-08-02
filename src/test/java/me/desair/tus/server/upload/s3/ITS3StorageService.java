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
}
