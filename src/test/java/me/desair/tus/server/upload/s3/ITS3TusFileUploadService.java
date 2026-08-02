package me.desair.tus.server.upload.s3;

import me.desair.tus.server.AbstractITTusFileUploadService;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * End-to-end integration test suite verifying {@link TusFileUploadService} backed by {@link
 * S3StorageService} and {@link S3LockingService} on MinIO. Extends {@link
 * AbstractITTusFileUploadService} to run all Tus 1.0.0 protocol use cases against S3 storage.
 */
public class ITS3TusFileUploadService extends AbstractITTusFileUploadService {

  private static GenericContainer<?> minio;
  private static S3Client s3Client;
  private static final String BUCKET = "test-tus-service-bucket";

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
    return createTusFileUploadService(UPLOAD_URI);
  }

  @Override
  protected TusFileUploadService createTusFileUploadService(String uploadUri) {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    S3StorageService s3Storage = new S3StorageService(s3Client, BUCKET);
    S3LockingService s3Locking = new S3LockingService(s3Client, BUCKET);
    S3ConcatenationService s3Concat = new S3ConcatenationService(s3Client, BUCKET, s3Storage);
    s3Storage.setUploadConcatenationService(s3Concat);

    return new TusFileUploadService()
        .withUploadUri(uploadUri)
        .withUploadStorageService(s3Storage)
        .withUploadLockingService(s3Locking)
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0)
        .withDownloadFeature()
        .withChunkedTransferDecoding(true);
  }
}
