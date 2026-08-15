package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Before;
import org.junit.Test;

/**
 * Offline unit tests for {@link AzureBlobUploadLock} verifying constructor parameter validation,
 * getters, executor management, and release lifecycle.
 */
public class AzureBlobUploadLockTest {

  private BlobLeaseClient leaseClient;
  private BlobClient lockBlob;

  @Before
  public void setUp() {
    BlobContainerClient containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .buildClient();
    lockBlob = containerClient.getBlobClient("locks/test.lock");
    leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullLeaseClient() {
    new AzureBlobUploadLock(null, lockBlob, "/test/upload/123");
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullLockBlob() {
    new AzureBlobUploadLock(leaseClient, null, "/test/upload/123");
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullUploadUri() {
    new AzureBlobUploadLock(leaseClient, lockBlob, null);
  }

  @Test
  public void testLockGettersAndReleaseWithExecutor() throws Exception {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(null, null, "/test/upload/12345", mockExecutor);

    assertEquals("/test/upload/12345", lock.getUploadUri());

    lock.release();
    verify(mockExecutor).shutdownNow();

    // Secondary release or close should be idempotent
    lock.release();
    lock.close();
    assertNotNull(lock);
  }

  @Test
  public void testRenewLeaseWhenReleasedIsNoOp() throws Exception {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(null, null, "/test/upload/12345", mockExecutor);

    lock.release();
    // Subsequent renewLease calls are no-ops when already marked released
    lock.renewLease();
    assertNotNull(lock);
  }
}
