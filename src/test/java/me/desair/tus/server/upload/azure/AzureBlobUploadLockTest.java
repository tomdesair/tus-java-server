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

  @Test
  public void testReleaseRemovesActiveStreamRegistration() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-123", new java.lang.ref.WeakReference<>(stream));

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, "/test/upload/upload-123", mockExecutor, "upload-123", activeStreams);

    // Verify release removes the upload ID from activeStreams
    lock.release();
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-123"));
    org.junit.Assert.assertFalse(stream.isInterrupted());
  }

  @Test
  public void testRenewLeaseFailureInterruptsActiveStream() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-failing", new java.lang.ref.WeakReference<>(stream));

    // Subclass to simulate Azure lease renewal failure without needing to mock
    // final Azure SDK classes.
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null,
            null,
            "/test/upload/upload-failing",
            mockExecutor,
            "upload-failing",
            activeStreams) {
          @Override
          void executeRenew() {
            throw new RuntimeException("Simulated Azure lease renewal failure");
          }
        };

    // Verify that failed renewal immediately aborts the active stream
    lock.renewLease();

    org.junit.Assert.assertTrue(stream.isInterrupted());
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-failing"));
    verify(mockExecutor).shutdownNow();
  }
}
