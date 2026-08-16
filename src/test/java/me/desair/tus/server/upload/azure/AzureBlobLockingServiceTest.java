package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import java.io.ByteArrayInputStream;
import me.desair.tus.server.upload.TimeBasedUploadIdFactory;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.Before;
import org.junit.Test;

/**
 * Offline unit tests for {@link AzureBlobLockingService} verifying parameter validation, prefix
 * normalization, URI parsing, and defensive handling.
 */
public class AzureBlobLockingServiceTest {

  private BlobContainerClient containerClient;
  private AzureBlobLockingService lockingService;

  @Before
  public void setUp() {
    containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .buildClient();
    lockingService = new AzureBlobLockingService(containerClient);
    TimeBasedUploadIdFactory idFactory = new TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/test/upload");
    lockingService.setIdFactory(idFactory);
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobLockingService(null);
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

  @Test(expected = NullPointerException.class)
  public void setIdFactoryShouldThrowOnNull() {
    lockingService.setIdFactory(null);
  }

  @Test
  public void lockUploadByUriShouldReturnNullOnInvalidUri() throws Exception {
    assertNull(lockingService.lockUploadByUri("invalid-uri-no-id"));
    assertNull(lockingService.lockUploadByUri(null));
  }

  @Test
  public void isLockedShouldReturnFalseForNullId() {
    assertFalse(lockingService.isLocked(null));
  }

  @Test
  public void registerInputStreamShouldDoNothingOnInvalidUriOrStandardStream() {
    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());

    // KISS: verifying invalid URI or non-interruptible stream registration handles gracefully
    // without exception
    lockingService.registerInputStream("invalid-uri", bais);
    lockingService.registerInputStream("/test/upload/12345", bais);
    lockingService.registerInputStream(null, bais);
  }

  @Test
  public void requestLockReleaseShouldDoNothingOnInvalidUri() {
    // KISS: verifying request release on invalid URI is no-op and does not throw
    lockingService.requestLockRelease("invalid-uri");
    lockingService.requestLockRelease(null);
  }

  @Test
  public void cleanupStaleLocksShouldNotThrow() throws Exception {
    // KISS: verifying method executes cleanly without throwing an exception
    lockingService.cleanupStaleLocks();
  }

  @Test
  public void closeShouldCleanUpResources() throws Exception {
    // KISS: verifying close executes idempotently without throwing an exception
    lockingService.close();
    lockingService.close();
  }

  @Test
  public void closeInterruptsActiveWatchdogThread() throws Exception {
    ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    lockingService.registerInputStream("/test/upload/88888", stream);
    org.junit.Assert.assertFalse(stream.isInterrupted());

    lockingService.close();
    org.junit.Assert.assertTrue(stream.isInterrupted());
  }
}
