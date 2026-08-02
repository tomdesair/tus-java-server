package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

public class ITS3StorageService {

  private static GenericContainer<?> minio;
  private static S3Client s3Client;
  private static final String BUCKET = "test-tus-bucket";

  private S3StorageService storageService;

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers MinIO test",
        TestUtils.isContainerRuntimeAvailable());

    minio = TestUtils.createMinioContainer();
    minio.start();

    s3Client = TestUtils.createS3Client(minio);
    TestUtils.createBucket(s3Client, BUCKET);
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
    storageService = new S3StorageService(s3Client, BUCKET);
  }

  @Test
  public void testFullUploadLifecycle() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(11L);

    UploadInfo created = storageService.create(info, "owner-1");
    assertNotNull(created);
    assertNotNull(created.getId());
    assertNotNull(created.getStorageUploadId());
    assertEquals(Long.valueOf(0), created.getOffset());

    byte[] bytes = "hello world".getBytes(StandardCharsets.UTF_8);
    UploadInfo updated = storageService.append(created, new ByteArrayInputStream(bytes));
    assertNotNull(updated);
    assertEquals(Long.valueOf(11), updated.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      byte[] retrieved = IOUtils.toByteArray(is);
      assertArrayEquals(bytes, retrieved);
    }

    storageService.terminateUpload(created);
    assertNull(storageService.getUploadInfo(created.getId()));
  }

  @Test
  public void testDeduplicationOnS3() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    UploadInfo parent = new UploadInfo();
    parent.setLength(10L);
    parent.setChecksum("hash-12345");
    parent.setChecksumAlgorithm(ChecksumAlgorithm.SHA256);

    UploadInfo createdParent = storageService.create(parent, "owner-1");
    storageService.append(createdParent, new ByteArrayInputStream("0123456789".getBytes()));

    UploadInfo found =
        storageService.getUploadInfoByChecksum("hash-12345", ChecksumAlgorithm.SHA256);
    assertNotNull(found);
    assertEquals(createdParent.getId(), found.getId());
  }
}
