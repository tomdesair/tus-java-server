package me.desair.tus.server.upload.s3;

import me.desair.tus.server.AbstractITRufhProtocol;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
import org.junit.AfterClass;
import org.junit.BeforeClass;

/**
 * End-to-end integration test suite verifying the IETF Resumable Uploads for HTTP (RUFH) protocol
 * implementation backed by {@link S3StorageService} and {@link S3LockingService} on MinIO.
 */
public class ITS3RufhProtocol extends AbstractITRufhProtocol {

  private static org.testcontainers.containers.GenericContainer<?> minio;
  private static software.amazon.awssdk.services.s3.S3Client s3Client;
  private static final String BUCKET = "test-rufh-s3-bucket";

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

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    S3StorageService s3Storage = new S3StorageService(s3Client, BUCKET);
    S3LockingService s3Locking = new S3LockingService(s3Client, BUCKET);
    S3ConcatenationService s3Concat = new S3ConcatenationService(s3Client, BUCKET, s3Storage);
    s3Storage.setUploadConcatenationService(s3Concat);

    return new TusFileUploadService()
        .withUploadUri(UPLOAD_URI)
        .withUploadStorageService(s3Storage)
        .withUploadLockingService(s3Locking)
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withDownloadFeature();
  }
}
