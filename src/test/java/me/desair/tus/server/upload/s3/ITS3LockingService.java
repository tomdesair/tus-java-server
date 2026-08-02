package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import me.desair.tus.server.TestUtils;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadLock;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;
import software.amazon.awssdk.services.s3.S3Client;

public class ITS3LockingService {

  private static GenericContainer<?> minio;
  private static S3Client s3Client;
  private static final String BUCKET = "test-lock-bucket";
  private static final String TEST_UUID = "24249a5b-01a4-4bf8-b67a-364273bb5a2e";

  private S3LockingService lockingService;

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
    lockingService = new S3LockingService(s3Client, BUCKET);
  }

  @Test
  public void testLockAcquireAndRelease() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/files/upload/" + TEST_UUID);
    assertNotNull(lock);
    assertTrue(lockingService.isLocked(new UploadId(TEST_UUID)));

    lock.close();
    org.junit.Assert.assertFalse(lockingService.isLocked(new UploadId(TEST_UUID)));
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void testConcurrentLockFails() throws Exception {
    UploadLock lock1 = lockingService.lockUploadByUri("/files/upload/" + TEST_UUID);
    try {
      lockingService.lockUploadByUri("/files/upload/" + TEST_UUID);
    } finally {
      if (lock1 != null) {
        lock1.close();
      }
    }
  }
}
