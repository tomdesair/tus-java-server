package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.minio.MinioClient;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadLock;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITS3LockingService {

  private static GenericContainer<?> minio;
  private static MinioClient minioClient;
  private static final String BUCKET = "test-locking-service-bucket";

  private S3LockingService lockingService;

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
    lockingService = new S3LockingService(minioClient, BUCKET);
  }

  @Test
  public void testLockAcquireAndRelease() throws Exception {
    String uri = "/test/upload/24249a5b-01a4-4bf8-b67a-364273bb5a21";
    UploadId id = new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a21");
    UploadLock lock = lockingService.lockUploadByUri(uri);
    assertNotNull(lock);

    // Verify upload is locked
    assertTrue(lockingService.isLocked(id));

    // Release lock
    lock.release();

    // Verify lock is released
    assertFalse(lockingService.isLocked(id));
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testConcurrentLockFails() throws Exception {
    String uri = "/test/upload/24249a5b-01a4-4bf8-b67a-364273bb5a22";
    UploadLock lock1 = lockingService.lockUploadByUri(uri);
    assertNotNull(lock1);

    try {
      // Second lock attempt on same URI should throw UploadAlreadyLockedException
      lockingService.lockUploadByUri(uri);
    } finally {
      lock1.release();
    }
  }
}
