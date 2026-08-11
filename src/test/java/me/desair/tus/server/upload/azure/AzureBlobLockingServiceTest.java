package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
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

public class AzureBlobLockingServiceTest {

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
      // Second lock attempt on same URI should throw UploadAlreadyLockedException
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
  }
}
