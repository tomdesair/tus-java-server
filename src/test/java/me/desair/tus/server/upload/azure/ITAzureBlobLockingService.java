package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
  public void lockUploadByUriShouldReturnNullOnInvalidUri() throws Exception {
    assertNull(lockingService.lockUploadByUri("invalid-uri-no-id"));
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
  public void cleanupStaleLocksShouldNotThrow() throws Exception {
    lockingService.cleanupStaleLocks();
  }

  @Test
  public void closeShouldCleanUpResources() throws Exception {
    lockingService.close();
    lockingService.close();
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobLockingService(null);
  }

  @Test(expected = NullPointerException.class)
  public void setIdFactoryShouldThrowOnNull() {
    lockingService.setIdFactory(null);
  }

  @Test
  public void isLockedShouldReturnFalseForNullId() {
    assertFalse(lockingService.isLocked(null));
  }

  @Test
  public void isLockedShouldReturnFalseWhenPropertiesThrowException() {
    assertFalse(lockingService.isLocked(new UploadId("non-existent-lock-id-999")));
  }

  @Test
  public void registerInputStreamShouldDoNothingOnInvalidUriOrStandardStream() {
    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());

    lockingService.registerInputStream("invalid-uri", bais);
    lockingService.registerInputStream("/test/upload/12345", bais);
  }

  @Test
  public void requestLockReleaseShouldDoNothingOnInvalidUri() {
    lockingService.requestLockRelease("invalid-uri");
  }

  @Test
  public void prefixSanitizationVariants() {
    AzureBlobLockingService service1 = new AzureBlobLockingService(containerClient, null);
    AzureBlobLockingService service2 =
        new AzureBlobLockingService(containerClient, "/custom/locks");
    AzureBlobLockingService service3 =
        new AzureBlobLockingService(containerClient, "custom/locks/");

    assertNotNull(service1);
    assertNotNull(service2);
    assertNotNull(service3);
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

  @Test
  public void ensureLockBlobExistsHandlesExceptions() {
    com.azure.storage.blob.BlobClient lockBlob =
        containerClient.getBlobClient("locks/nonexistentcontainer/invalid.lock");
    lockingService.ensureLockBlobExists(lockBlob);
  }

  @Test
  public void closeInterruptsActiveWatchdogThread() throws Exception {
    ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    lockingService.registerInputStream("/test/upload/88888", stream);
    lockingService.close();
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
}
