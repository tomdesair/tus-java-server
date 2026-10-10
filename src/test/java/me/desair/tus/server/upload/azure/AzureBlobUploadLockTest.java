package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
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
  public void testNullRenewalExecutorAndNullLeaseClient() {
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(null, null, "/test/upload/null-exec", null, null, null);
    org.junit.Assert.assertNull(lock.getLeaseClient());
    assertEquals("/test/upload/null-exec", lock.getUploadUri());
    lock.executeRenew();
    lock.renewLease();
    lock.release();
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

    // When the lease has already expired, renewal failure immediately aborts
    lock.setLeaseExpiresAt(System.currentTimeMillis() - 1000L);
    lock.renewLease();

    org.junit.Assert.assertTrue(stream.isInterrupted());
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-failing"));
    verify(mockExecutor).shutdownNow();
  }

  @Test
  public void testRenewLeaseTransientFailureTolerated() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-transient", new java.lang.ref.WeakReference<>(stream));

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null,
            null,
            "/test/upload/upload-transient",
            mockExecutor,
            "upload-transient",
            activeStreams) {
          @Override
          void executeRenew() {
            throw new RuntimeException("Simulated transient network glitch");
          }
        };

    // Lease valid for another 30 seconds: transient blip must NOT abort
    lock.setLeaseExpiresAt(System.currentTimeMillis() + 30000L);
    lock.renewLease();

    org.junit.Assert.assertFalse(stream.isInterrupted());
    org.junit.Assert.assertTrue(activeStreams.containsKey("upload-transient"));
    org.mockito.Mockito.verify(mockExecutor, org.mockito.Mockito.never()).shutdownNow();
  }

  @Test
  public void testRenewLeaseLostInterruptsActiveStream() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-lost", new java.lang.ref.WeakReference<>(stream));

    HttpResponse response = mock(HttpResponse.class);
    org.mockito.Mockito.when(response.getStatusCode()).thenReturn(409);
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-ms-error-code", BlobErrorCode.LEASE_NOT_PRESENT_WITH_LEASE_OPERATION.toString());
    org.mockito.Mockito.when(response.getHeaders()).thenReturn(headers);

    BlobStorageException leaseLostException =
        new BlobStorageException(
            "Lease lost", response, BlobErrorCode.LEASE_NOT_PRESENT_WITH_LEASE_OPERATION);

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, "/test/upload/upload-lost", mockExecutor, "upload-lost", activeStreams) {
          @Override
          void executeRenew() {
            throw leaseLostException;
          }
        };

    // Even if lease was theoretically valid, leaseLost aborts immediately
    lock.setLeaseExpiresAt(System.currentTimeMillis() + 30000L);
    lock.renewLease();

    org.junit.Assert.assertTrue(stream.isInterrupted());
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-lost"));
    verify(mockExecutor).shutdownNow();
  }

  @Test
  public void testRenewLeaseConflictErrorInterruptsStream() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-conflict", new java.lang.ref.WeakReference<>(stream));

    HttpResponse response = mock(HttpResponse.class);
    org.mockito.Mockito.when(response.getStatusCode()).thenReturn(409);
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-ms-error-code", "Conflict");
    org.mockito.Mockito.when(response.getHeaders()).thenReturn(headers);

    BlobStorageException conflictException = new BlobStorageException("Conflict", response, null);

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null,
            null,
            "/test/upload/upload-conflict",
            mockExecutor,
            "upload-conflict",
            activeStreams) {
          @Override
          void executeRenew() {
            throw conflictException;
          }
        };

    lock.setLeaseExpiresAt(System.currentTimeMillis() + 30000L);
    lock.renewLease();

    org.junit.Assert.assertTrue(stream.isInterrupted());
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-conflict"));
    verify(mockExecutor).shutdownNow();
  }

  @Test
  public void testRenewLeaseLostWithoutActiveStreamHandlesCleanly() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);

    // activeStreams and uploadId both null
    AzureBlobUploadLock lockWithoutStreams =
        new AzureBlobUploadLock(null, null, "/test/upload/upload-null", mockExecutor, null, null) {
          @Override
          void executeRenew() {
            throw new RuntimeException("Renewal failed");
          }
        };

    lockWithoutStreams.setLeaseExpiresAt(System.currentTimeMillis() - 1000L);
    lockWithoutStreams.renewLease();
    verify(mockExecutor).shutdownNow();

    // activeStreams present but no stream registered for uploadId
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();
    ScheduledExecutorService mockExecutor2 = mock(ScheduledExecutorService.class);

    AzureBlobUploadLock lockWithEmptyStreams =
        new AzureBlobUploadLock(
            null, null, "/test/upload/upload-empty", mockExecutor2, "upload-empty", activeStreams) {
          @Override
          void executeRenew() {
            throw new RuntimeException("Renewal failed");
          }
        };

    lockWithEmptyStreams.setLeaseExpiresAt(System.currentTimeMillis() - 1000L);
    lockWithEmptyStreams.renewLease();
    verify(mockExecutor2).shutdownNow();
  }
}
