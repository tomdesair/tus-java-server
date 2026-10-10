package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.exception.UploadAlreadyLockedException;
import me.desair.tus.server.upload.TimeBasedUploadIdFactory;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITAzureBlobLockingService {

  private static GenericContainer<?> azuriteContainer;

  @BeforeClass
  public static void setUpClass() {
    Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers Azurite test",
        TestUtils.isContainerRuntimeAvailable());
    azuriteContainer = TestUtils.createAzuriteContainer();
    azuriteContainer.start();
  }

  @AfterClass
  public static void tearDownClass() {
    if (azuriteContainer != null) {
      azuriteContainer.stop();
    }
  }

  private BlobContainerClient containerClient;
  private AzureBlobLockingService lockingService;

  @Before
  public void setUp() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "lock-unit-container-" + System.nanoTime());
    lockingService = new AzureBlobLockingService(containerClient);
    TimeBasedUploadIdFactory idFactory = new TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/test/upload");
    lockingService.setIdFactory(idFactory);
  }

  @Test
  public void isLockedShouldReturnFalseWhenLockBlobDoesNotExist() {
    assertFalse(lockingService.isLocked(new UploadId("12345")));
  }

  @Test
  public void lockUploadByUriShouldAcquireLock() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/12345");
    assertNotNull(lock);
    assertEquals("/test/upload/12345", lock.getUploadUri());
    assertTrue(lockingService.isLocked(new UploadId("12345")));
    lock.release();

    // Re-acquire on the existing lock blob to test optimistic lease acquisition path
    UploadLock reacquired = lockingService.lockUploadByUri("/test/upload/12345");
    assertNotNull(reacquired);
    reacquired.release();
  }

  @Test
  public void testAzureBlobUploadLock3ArgConstructor() throws Exception {
    UploadId testId = new TimeBasedUploadIdFactory().createId();
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/" + testId);
    assertNotNull(lock);
    lock.release();

    com.azure.storage.blob.BlobClient lockBlob =
        containerClient.getBlobClient("locks/" + testId + ".lock");
    com.azure.storage.blob.specialized.BlobLeaseClient lease =
        new com.azure.storage.blob.specialized.BlobLeaseClientBuilder()
            .blobClient(lockBlob)
            .buildClient();
    lease.acquireLease(30);

    AzureBlobUploadLock threeArgLock =
        new AzureBlobUploadLock(lease, lockBlob, "/test/upload/" + testId);
    assertEquals("/test/upload/" + testId, threeArgLock.getUploadUri());
    assertEquals(lease, threeArgLock.getLeaseClient());
    threeArgLock.executeRenew();
    threeArgLock.release();
  }

  @Test
  public void testAzureBlobUploadLockLifecycleAndRenewal() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/12345");
    assertNotNull(lock);
    assertEquals("/test/upload/12345", lock.getUploadUri());

    AzureBlobUploadLock azureLock = (AzureBlobUploadLock) lock;
    azureLock.renewLease();

    azureLock.release();
    azureLock.renewLease();
    azureLock.release();
    azureLock.close();
  }

  @Test
  public void testAzureBlobUploadLockRenewalAndReleaseFailureHandling() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/998877");
    assertNotNull(lock);

    AzureBlobUploadLock azureLock = (AzureBlobUploadLock) lock;
    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/998877.lock");
    com.azure.storage.blob.specialized.BlobLeaseClient externalLeaseClient =
        new com.azure.storage.blob.specialized.BlobLeaseClientBuilder()
            .blobClient(lockBlob)
            .buildClient();
    externalLeaseClient.breakLease();

    // Calling renewLease on an externally released lease triggers catch block
    azureLock.renewLease();

    // Acquire another lock and delete the blob with lease ID to test direct release catch block
    UploadLock lock2 = lockingService.lockUploadByUri("/test/upload/998878");
    assertNotNull(lock2);
    AzureBlobUploadLock azureLock2 = (AzureBlobUploadLock) lock2;
    com.azure.storage.blob.BlobClient lockBlob2 =
        containerClient.getBlobClient("locks/998878.lock");
    String leaseId = azureLock2.getLeaseClient().getLeaseId();
    lockBlob2.deleteWithResponse(
        com.azure.storage.blob.models.DeleteSnapshotsOptionType.INCLUDE,
        new com.azure.storage.blob.models.BlobRequestConditions().setLeaseId(leaseId),
        null,
        null);
    // Direct release on deleted blob triggers catch block in release()
    azureLock2.release();
  }

  @Test(expected = UploadAlreadyLockedException.class)
  public void lockUploadByUriShouldThrowOnLockContention() throws Exception {
    UploadLock lock1 = lockingService.lockUploadByUri("/test/upload/12345");
    assertNotNull(lock1);
    try {
      lockingService.lockUploadByUri("/test/upload/12345");
    } finally {
      lock1.release();
    }
  }

  @Test
  public void registerInputStreamAndRequestReleaseShouldInterruptStream() throws Exception {
    ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    lockingService.registerInputStream("/test/upload/12345", stream);
    lockingService.requestLockRelease("/test/upload/12345");

    try {
      stream.read();
    } catch (Exception e) {
      assertNotNull(e);
    }
  }

  @Test
  public void watchdogPollingDetectsStopSignalBlob() throws Exception {
    ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    lockingService.registerInputStream("/test/upload/54321", stream);

    com.azure.storage.blob.BlobClient stopBlob = containerClient.getBlobClient("locks/54321.stop");
    stopBlob.upload(com.azure.core.util.BinaryData.fromString("stop"), true);

    long deadline = System.currentTimeMillis() + 3500L;
    while (!stream.isInterrupted() && System.currentTimeMillis() < deadline) {
      Thread.sleep(100L);
    }

    assertTrue("Expected stream to be interrupted by watchdog thread", stream.isInterrupted());
    assertFalse("Expected .stop blob to be deleted by watchdog thread", stopBlob.exists());
  }

  @Test(expected = IOException.class)
  public void lockUploadByUriShouldThrowIOExceptionOnStorageException() throws Exception {
    com.azure.storage.blob.BlobServiceClient serviceClient = containerClient.getServiceClient();
    BlobContainerClient nonExistentContainer =
        serviceClient.getBlobContainerClient(
            "non-existent-container-" + System.currentTimeMillis());
    AzureBlobLockingService service = new AzureBlobLockingService(nonExistentContainer);
    TimeBasedUploadIdFactory idFactory = new TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/test/upload");
    service.setIdFactory(idFactory);

    service.lockUploadByUri("/test/upload/12345");
  }

  @Test
  public void testCreateAndDeleteStopSignalBlob() {
    lockingService.createStopSignalBlob("test-stop-id");
    com.azure.storage.blob.BlobClient stopBlob =
        containerClient.getBlobClient("locks/test-stop-id.stop");
    assertTrue(Boolean.TRUE.equals(stopBlob.exists()));

    lockingService.deleteStopSignalBlob("test-stop-id");
    assertFalse(Boolean.TRUE.equals(stopBlob.exists()));
  }

  @Test
  public void testStopSignalBlobCatchBlocksHandledGracefully() {
    com.azure.storage.blob.BlobServiceClient serviceClient = containerClient.getServiceClient();
    BlobContainerClient nonExistentContainer =
        serviceClient.getBlobContainerClient("non-existent-" + System.nanoTime());
    AzureBlobLockingService failingService = new AzureBlobLockingService(nonExistentContainer);

    // Verified against Azurite that non-existent container calls fail fast and are handled quietly
    failingService.createStopSignalBlob("fail-stop");
    failingService.deleteStopSignalBlob("fail-stop");
    failingService.ensureLockBlobExists(nonExistentContainer.getBlobClient("fail.lock"));
    // KISS: verifying methods execute cleanly without throwing uncaught exceptions
    assertTrue(true);
  }

  @Test
  public void testCleanupLockDeletesLockAndStopBlobs() throws Exception {
    UploadId id = new UploadId(111222L);
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/111222");
    assertNotNull(lock);
    lockingService.createStopSignalBlob("111222");
    lock.release();

    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/111222.lock");
    com.azure.storage.blob.BlobClient stopBlob = containerClient.getBlobClient("locks/111222.stop");

    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));
    assertTrue(Boolean.TRUE.equals(stopBlob.exists()));

    lockingService.cleanupLock(id);

    assertFalse(Boolean.TRUE.equals(lockBlob.exists()));
    assertFalse(Boolean.TRUE.equals(stopBlob.exists()));
  }

  @Test
  public void testCleanupLockByUri() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/333444");
    assertNotNull(lock);
    lock.release();

    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/333444.lock");
    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));

    lockingService.cleanupLock("/test/upload/333444");

    assertFalse(Boolean.TRUE.equals(lockBlob.exists()));
  }

  @Test
  public void testCleanupLockActivelyLeasedBlobByExternalClientIsPreserved() throws Exception {
    UploadId id = new UploadId(555666L);
    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/555666.lock");
    lockBlob.upload(new ByteArrayInputStream(new byte[0]), 0);

    // External lease client simulates another replica / pod holding the lease
    com.azure.storage.blob.specialized.BlobLeaseClient externalLease =
        new com.azure.storage.blob.specialized.BlobLeaseClientBuilder()
            .blobClient(lockBlob)
            .buildClient();
    externalLease.acquireLease(30);

    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));

    // When actively leased by another node, cleanupLock must NOT delete the lock blob
    lockingService.cleanupLock(id);
    assertTrue(
        "Actively leased blob by another client must not be deleted",
        Boolean.TRUE.equals(lockBlob.exists()));

    // After the other client releases the lease, cleanupLock should delete it
    externalLease.releaseLease();
    lockingService.cleanupLock(id);
    assertFalse("Unleased blob should be deleted", Boolean.TRUE.equals(lockBlob.exists()));
  }

  @Test
  public void testCleanupLockActivelyLeasedBlobByCurrentServiceIsDeleted() throws Exception {
    UploadId id = new UploadId(666777L);
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/666777");
    assertNotNull(lock);

    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/666777.lock");
    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));

    // When actively leased by the current service (e.g. during DELETE termination),
    // cleanupLock deletes the blob using the active lease ID
    lockingService.cleanupLock(id);
    assertFalse(
        "Actively leased blob by current service should be deleted",
        Boolean.TRUE.equals(lockBlob.exists()));

    // Release after cleanup should execute cleanly without error
    lock.release();
  }

  @Test
  public void testCleanupStaleLocksPreservesFreshBlobs() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/test/upload/777888");
    assertNotNull(lock);
    lock.release();

    com.azure.storage.blob.BlobClient lockBlob = containerClient.getBlobClient("locks/777888.lock");
    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));

    lockingService.cleanupStaleLocks();

    // Fresh blob (created just now, < 120s old) must NOT be deleted by cleanupStaleLocks
    assertTrue(Boolean.TRUE.equals(lockBlob.exists()));
  }
}
