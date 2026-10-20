package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.util.concurrent.ScheduledFuture;
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
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    doReturn(mockFuture)
        .when(mockExecutor)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(null, null, "/test/upload/12345", mockExecutor);

    assertEquals("/test/upload/12345", lock.getUploadUri());

    lock.release();
    verify(mockFuture).cancel(true);
    verify(mockExecutor, never()).shutdownNow();

    // Secondary release or close should be idempotent
    lock.release();
    lock.close();
    assertNotNull(lock);
  }

  @Test
  public void testNullRenewalExecutorAndNullLeaseClient() {
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, "/test/upload/null-exec", (ScheduledExecutorService) null, null, null);
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
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    doReturn(mockFuture)
        .when(mockExecutor)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
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
    verify(mockFuture).cancel(true);
    verify(mockExecutor, never()).shutdownNow();
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
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    doReturn(mockFuture)
        .when(mockExecutor)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
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
    verify(mockFuture).cancel(true);
    verify(mockExecutor, never()).shutdownNow();
  }

  @Test
  public void testRenewLeaseConflictErrorInterruptsStream() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    doReturn(mockFuture)
        .when(mockExecutor)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
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
    verify(mockFuture).cancel(true);
    verify(mockExecutor, never()).shutdownNow();
  }

  @Test
  public void testRenewLeaseLostWithoutActiveStreamHandlesCleanly() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> mockFuture = mock(ScheduledFuture.class);
    doReturn(mockFuture)
        .when(mockExecutor)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());

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
    verify(mockFuture).cancel(true);
    verify(mockExecutor, never()).shutdownNow();

    // activeStreams present but no stream registered for uploadId
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();
    ScheduledExecutorService mockExecutor2 = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> mockFuture2 = mock(ScheduledFuture.class);
    doReturn(mockFuture2)
        .when(mockExecutor2)
        .scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());

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
    verify(mockFuture2).cancel(true);
    verify(mockExecutor2, never()).shutdownNow();
  }

  @Test
  public void testReleaseRemovesActiveLockAndSkipsReleaseWhenBlobDeleted() {
    BlobLeaseClient realLease =
        new BlobLeaseClientBuilder().blobClient(lockBlob).leaseId("mock-lease-123").buildClient();
    java.util.Map<String, AzureBlobUploadLock> activeLocks =
        new java.util.concurrent.ConcurrentHashMap<>();

    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            realLease,
            lockBlob,
            "/test/upload/lock-deleted-test",
            mockExecutor,
            "lock-deleted-test",
            null,
            activeLocks);

    activeLocks.put("lock-deleted-test", lock);
    org.junit.Assert.assertFalse(lock.isBlobDeleted());

    lock.markBlobDeleted();
    org.junit.Assert.assertTrue(lock.isBlobDeleted());

    // Release skips leaseClient.releaseLease() because blob is marked deleted,
    // so no network call is attempted against the unmocked endpoint.
    lock.release();

    // Verify activeLocks is cleared
    org.junit.Assert.assertFalse(activeLocks.containsKey("lock-deleted-test"));
  }

  @Test
  public void testConstructors5ArgAnd6Arg() {
    AzureBlobUploadLock lock5 =
        new AzureBlobUploadLock(
            leaseClient,
            lockBlob,
            "/test/upload/5",
            "5",
            new java.util.concurrent.ConcurrentHashMap<>());
    assertNotNull(lock5);

    AzureBlobUploadLock lock6 =
        new AzureBlobUploadLock(
            leaseClient,
            lockBlob,
            "/test/upload/6",
            "6",
            new java.util.concurrent.ConcurrentHashMap<>(),
            new java.util.concurrent.ConcurrentHashMap<>());
    assertNotNull(lock6);
  }

  @Test
  public void testReleaseLockResourceBlobStorageExceptionFallbackToReleaseLease() {
    BlobClient mockBlob = mock(BlobClient.class);
    BlobClient mockStopBlob = mock(BlobClient.class);
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);

    HttpResponse response = mock(HttpResponse.class);
    org.mockito.Mockito.when(response.getStatusCode()).thenReturn(500);
    BlobStorageException bse = new BlobStorageException("Server error", response, null);
    org.mockito.Mockito.doThrow(bse).when(mockBlob).deleteWithResponse(any(), any(), any(), any());

    boolean[] fallbackCalled = new boolean[] {false};
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            leaseClient,
            mockBlob,
            mockStopBlob,
            "/test/upload/fallback-test",
            mockExecutor,
            "fallback-test",
            null,
            null) {
          @Override
          void fallbackReleaseLease() {
            fallbackCalled[0] = true;
          }
        };

    lock.release();
    org.junit.Assert.assertTrue(fallbackCalled[0]);
    verify(mockStopBlob).deleteIfExists();
  }

  @Test
  public void testReleaseLockResourceGenericExceptionFallbackToReleaseLease() {
    BlobClient mockBlob = mock(BlobClient.class);
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);

    org.mockito.Mockito.doThrow(new RuntimeException("Network error"))
        .when(mockBlob)
        .deleteWithResponse(any(), any(), any(), any());

    boolean[] fallbackCalled = new boolean[] {false};
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            leaseClient,
            mockBlob,
            "/test/upload/generic-fallback",
            mockExecutor,
            "generic-fallback",
            null,
            null) {
          @Override
          void fallbackReleaseLease() {
            fallbackCalled[0] = true;
          }
        };

    lock.release();
    org.junit.Assert.assertTrue(fallbackCalled[0]);
  }

  @Test
  public void testReleaseLockResourceBlobStorageException404DoesNotFallback() {
    BlobClient mockBlob = mock(BlobClient.class);
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);

    HttpResponse response = mock(HttpResponse.class);
    org.mockito.Mockito.when(response.getStatusCode()).thenReturn(404);
    BlobStorageException bse = new BlobStorageException("Not found", response, null);
    org.mockito.Mockito.doThrow(bse).when(mockBlob).deleteWithResponse(any(), any(), any(), any());

    boolean[] fallbackCalled = new boolean[] {false};
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            leaseClient, mockBlob, "/test/upload/404-test", mockExecutor, "404-test", null, null) {
          @Override
          void fallbackReleaseLease() {
            fallbackCalled[0] = true;
          }
        };

    lock.release();
    org.junit.Assert.assertFalse(fallbackCalled[0]);
  }

  @Test
  public void testRenewLeaseStatus412InterruptsStream() {
    ScheduledExecutorService mockExecutor = mock(ScheduledExecutorService.class);
    java.util.Map<
            String, java.lang.ref.WeakReference<me.desair.tus.server.util.InterruptibleInputStream>>
        activeStreams = new java.util.concurrent.ConcurrentHashMap<>();

    me.desair.tus.server.util.InterruptibleInputStream stream =
        new me.desair.tus.server.util.InterruptibleInputStream(
            new java.io.ByteArrayInputStream("test".getBytes()));
    activeStreams.put("upload-412", new java.lang.ref.WeakReference<>(stream));

    HttpResponse response = mock(HttpResponse.class);
    org.mockito.Mockito.when(response.getStatusCode()).thenReturn(412);
    BlobStorageException status412Ex =
        new BlobStorageException("Precondition failed", response, null);

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, "/test/upload/upload-412", mockExecutor, "upload-412", activeStreams) {
          @Override
          void executeRenew() {
            throw status412Ex;
          }
        };

    lock.setLeaseExpiresAt(System.currentTimeMillis() + 30000L);
    lock.renewLease();

    org.junit.Assert.assertTrue(stream.isInterrupted());
    org.junit.Assert.assertFalse(activeStreams.containsKey("upload-412"));
  }

  @Test
  public void testFallbackReleaseLeaseWhenLeaseClientNull() {
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, "/test/upload/fallback-null", (ScheduledExecutorService) null, null, null);
    // KISS: leaseClient is null, verifies method executes cleanly without network calls
    lock.fallbackReleaseLease();
    assertNotNull(lock);
  }

  @Test
  public void testFallbackReleaseLeaseWhenLeaseClientThrows() {
    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null,
            null,
            "/test/upload/fallback-throw",
            (ScheduledExecutorService) null,
            null,
            null) {
          @Override
          void executeReleaseLease() {
            throw new RuntimeException("Release failed");
          }
        };
    lock.fallbackReleaseLease();
    assertNotNull(lock);
  }

  @Test
  public void testReleaseHandlesStopBlobExceptionQuietly() {
    BlobClient mockStop = mock(BlobClient.class);
    org.mockito.Mockito.when(mockStop.deleteIfExists())
        .thenThrow(new RuntimeException("Stop delete error"));

    AzureBlobUploadLock lock =
        new AzureBlobUploadLock(
            null, null, mockStop, "/test/upload/stop-ex", null, "stop-ex", null, null);
    lock.release();
    assertNotNull(lock);
  }
}
