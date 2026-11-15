package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.LeaseStateType;
import com.azure.storage.blob.models.ListBlobsOptions;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import me.desair.tus.server.upload.TimeBasedUploadIdFactory;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

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
    lockingService =
        new AzureBlobLockingService(
            containerClient, AzureBlobLockingService.DEFAULT_LOCKS_PREFIX, null);
    TimeBasedUploadIdFactory idFactory = new TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/test/upload");
    lockingService.setIdFactory(idFactory);
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobLockingService(null);
  }

  @Test
  public void prefixSanitizationVariants() throws Exception {
    AzureBlobLockingService service1 = new AzureBlobLockingService(containerClient, null);
    AzureBlobLockingService service2 =
        new AzureBlobLockingService(containerClient, "/custom/locks");
    AzureBlobLockingService service3 =
        new AzureBlobLockingService(containerClient, "custom/locks/");

    try {
      assertNotNull(service1);
      assertNotNull(service2);
      assertNotNull(service3);
    } finally {
      service1.close();
      service2.close();
      service3.close();
    }
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
  public void testUnregisterInputStreamRemovesFromActiveStreams() {
    ByteArrayInputStream bais = new ByteArrayInputStream("data".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    lockingService.registerInputStream("/test/upload/88888", stream);
    assertTrue(lockingService.activeStreams.containsKey("88888"));

    lockingService.unregisterInputStream("/test/upload/88888");
    assertFalse(lockingService.activeStreams.containsKey("88888"));

    // Null or invalid URI handles gracefully without throwing
    lockingService.unregisterInputStream(null);
    lockingService.unregisterInputStream("invalid-uri");
  }

  @Test
  public void requestLockReleaseShouldDoNothingOnInvalidUri() {
    // KISS: verifying request release on invalid URI is no-op and does not throw
    lockingService.requestLockRelease("invalid-uri");
    lockingService.requestLockRelease(null);
  }

  @Test
  public void deleteLockBlobIfUnleasedNullReturnsFalse() {
    assertFalse(lockingService.deleteLockBlobIfUnleased(null));
  }

  @Test
  public void deleteLockBlobWithLeaseNullArguments() {
    assertFalse(lockingService.deleteLockBlobWithLease(null, "lease-id"));
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    assertFalse(lockingService.deleteLockBlobWithLease(mockBlob, null));
  }

  @Test
  public void deleteLockBlobWithLeaseExceptionHandled() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    Mockito.doThrow(new RuntimeException("Simulated delete lease error"))
        .when(mockBlob)
        .deleteWithResponse(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
    assertFalse(lockingService.deleteLockBlobWithLease(mockBlob, "lease-id"));
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
    assertFalse(stream.isInterrupted());

    lockingService.close();
    assertTrue(stream.isInterrupted());
  }

  @Test
  public void testPollStopSignalsCleansStaleWeakReferences() {
    lockingService.activeStreams.put("stale-upload-id", new java.lang.ref.WeakReference<>(null));
    assertTrue(lockingService.activeStreams.containsKey("stale-upload-id"));

    lockingService.pollStopSignals();

    assertFalse(lockingService.activeStreams.containsKey("stale-upload-id"));
  }

  @Test
  public void deleteLockBlobIfUnleasedCatchesExceptionAndReturnsFalse() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    Mockito.when(mockBlob.exists()).thenThrow(new RuntimeException("Simulated exists failure"));
    assertFalse(lockingService.deleteLockBlobIfUnleased(mockBlob));
  }

  @Test
  public void testCleanupStaleLocksWithVariousBlobItems() throws Exception {
    OffsetDateTime oldDate = OffsetDateTime.now().minusSeconds(200);
    OffsetDateTime freshDate = OffsetDateTime.now();

    BlobItem nullPropsItem = createTestBlobItemWithNullProps("locks/test.lock");
    BlobItem staleStopItem = createTestBlobItem("locks/stale.stop", oldDate, null);
    BlobItem freshStopItem = createTestBlobItem("locks/fresh.stop", freshDate, null);
    BlobItem leasedLockItem =
        createTestBlobItem("locks/leased.lock", oldDate, LeaseStateType.LEASED);
    BlobItem staleUnleasedLockItem =
        createTestBlobItem("locks/stale.lock", oldDate, LeaseStateType.AVAILABLE);
    BlobItem otherItem = createTestBlobItem("locks/other.txt", oldDate, null);

    List<BlobItem> items =
        Arrays.asList(
            null,
            nullPropsItem,
            staleStopItem,
            freshStopItem,
            leasedLockItem,
            staleUnleasedLockItem,
            otherItem);

    BlobClient mockStaleBlob = Mockito.mock(BlobClient.class);

    AzureBlobLockingService mockService =
        new AzureBlobLockingService(containerClient) {
          @Override
          Iterable<BlobItem> listBlobs(ListBlobsOptions options) {
            return items;
          }

          @Override
          BlobClient getBlobClient(String blobName) {
            return mockStaleBlob;
          }

          @Override
          boolean deleteLockBlobIfUnleased(BlobClient lockBlob) {
            return true;
          }
        };

    mockService.cleanupStaleLocks();
    Mockito.verify(mockStaleBlob).deleteIfExists();
  }

  @Test(expected = IOException.class)
  public void testCleanupStaleLocksThrowsIOExceptionOnListFailure() throws Exception {
    AzureBlobLockingService failingService =
        new AzureBlobLockingService(containerClient) {
          @Override
          Iterable<BlobItem> listBlobs(ListBlobsOptions options) {
            throw new RuntimeException("Simulated list failure");
          }
        };
    failingService.cleanupStaleLocks();
  }

  @Test
  public void ensureLockBlobExistsCatchesGenericException() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    Mockito.doThrow(new RuntimeException("Simulated upload error"))
        .when(mockBlob)
        .upload(Mockito.any(), Mockito.anyLong());
    // KISS: verify method catches generic exception without propagating
    lockingService.ensureLockBlobExists(mockBlob);
  }

  private BlobItem createTestBlobItem(
      String name, OffsetDateTime lastModified, LeaseStateType leaseState) {
    com.azure.storage.blob.implementation.models.BlobName blobName =
        new com.azure.storage.blob.implementation.models.BlobName()
            .setContent(name)
            .setEncoded(false);
    com.azure.storage.blob.implementation.models.BlobItemPropertiesInternal props =
        new com.azure.storage.blob.implementation.models.BlobItemPropertiesInternal()
            .setLastModified(lastModified)
            .setLeaseState(leaseState);
    com.azure.storage.blob.implementation.models.BlobItemInternal itemInternal =
        new com.azure.storage.blob.implementation.models.BlobItemInternal()
            .setName(blobName)
            .setProperties(props);
    return com.azure.storage.blob.implementation.util.ModelHelper.populateBlobItem(itemInternal);
  }

  private BlobItem createTestBlobItemWithNullProps(String name) {
    com.azure.storage.blob.implementation.models.BlobName blobName =
        new com.azure.storage.blob.implementation.models.BlobName()
            .setContent(name)
            .setEncoded(false);
    com.azure.storage.blob.implementation.models.BlobItemInternal itemInternal =
        new com.azure.storage.blob.implementation.models.BlobItemInternal().setName(blobName);
    return com.azure.storage.blob.implementation.util.ModelHelper.populateBlobItem(itemInternal);
  }

  @Test
  public void testDeleteStopSignalBlobCatchesException() {
    AzureBlobLockingService service =
        new AzureBlobLockingService(containerClient) {
          @Override
          BlobClient getBlobClient(String name) {
            BlobClient mock = Mockito.mock(BlobClient.class);
            Mockito.doThrow(new RuntimeException("Delete stop error")).when(mock).deleteIfExists();
            return mock;
          }
        };
    // KISS: verify method swallows exception without throwing
    service.deleteStopSignalBlob("upload-test");
  }

  @Test
  public void deleteLockBlobIfUnleasedAvailableLeaseStateDeletes() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    Mockito.when(mockBlob.exists()).thenReturn(true);
    com.azure.storage.blob.models.BlobProperties props =
        createTestBlobProperties(LeaseStateType.AVAILABLE);
    Mockito.when(mockBlob.getProperties()).thenReturn(props);

    assertTrue(lockingService.deleteLockBlobIfUnleased(mockBlob));
    Mockito.verify(mockBlob).deleteIfExists();
  }

  @Test
  public void deleteLockBlobIfUnleasedLeasedDoesNotDelete() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    Mockito.when(mockBlob.exists()).thenReturn(true);
    com.azure.storage.blob.models.BlobProperties props =
        createTestBlobProperties(LeaseStateType.LEASED);
    Mockito.when(mockBlob.getProperties()).thenReturn(props);

    assertFalse(lockingService.deleteLockBlobIfUnleased(mockBlob));
    Mockito.verify(mockBlob, Mockito.never()).deleteIfExists();
  }

  @Test
  public void deleteLockBlobWithLeaseSuccess() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    assertTrue(lockingService.deleteLockBlobWithLease(mockBlob, "valid-lease-id"));
    Mockito.verify(mockBlob)
        .deleteWithResponse(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());
  }

  @Test
  public void ensureLockBlobExistsCatchesBlobStorageException() {
    BlobClient mockBlob = Mockito.mock(BlobClient.class);
    com.azure.core.http.HttpResponse response =
        Mockito.mock(com.azure.core.http.HttpResponse.class);
    Mockito.when(response.getStatusCode()).thenReturn(500);
    com.azure.storage.blob.models.BlobStorageException bse =
        new com.azure.storage.blob.models.BlobStorageException("Internal error", response, null);
    Mockito.doThrow(bse)
        .when(mockBlob)
        .upload(Mockito.any(com.azure.core.util.BinaryData.class), Mockito.anyBoolean());
    lockingService.ensureLockBlobExists(mockBlob);
  }

  private com.azure.storage.blob.models.BlobProperties createTestBlobProperties(
      LeaseStateType leaseState) {
    return new com.azure.storage.blob.models.BlobProperties(
        null,
        OffsetDateTime.now(),
        "etag",
        0L,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        leaseState,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  public void testPollStopSignalsCatchesException() {
    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    AzureBlobLockingService service =
        new AzureBlobLockingService(
            containerClient, AzureBlobLockingService.DEFAULT_LOCKS_PREFIX, null) {
          @Override
          BlobClient getBlobClient(String name) {
            throw new RuntimeException("Stop blob check failed");
          }
        };
    service.activeStreams.put("poll-err", new java.lang.ref.WeakReference<>(stream));
    service.pollStopSignals();
    assertNotNull(service);
  }

  @Test
  public void testPollStopSignalsDetectsStopBlobAndInterrupts() {
    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    BlobClient mockStopBlob = Mockito.mock(BlobClient.class);
    Mockito.when(mockStopBlob.exists()).thenReturn(true);

    AzureBlobLockingService service =
        new AzureBlobLockingService(
            containerClient, AzureBlobLockingService.DEFAULT_LOCKS_PREFIX, null) {
          @Override
          BlobClient getBlobClient(String name) {
            return mockStopBlob;
          }
        };
    service.activeStreams.put("poll-stop", new java.lang.ref.WeakReference<>(stream));
    service.pollStopSignals();

    assertTrue(stream.isInterrupted());
    Mockito.verify(mockStopBlob).deleteIfExists();
  }

  @Test
  public void testLockUploadNullReturnsNull() throws Exception {
    assertNull(lockingService.lockUpload(null));
  }

  @Test
  public void testCleanupLock() {
    BlobClient mockLock = Mockito.mock(BlobClient.class);
    BlobClient mockStop = Mockito.mock(BlobClient.class);
    AzureBlobLockingService service =
        new AzureBlobLockingService(containerClient) {
          @Override
          BlobClient getBlobClient(String name) {
            if (name.endsWith(".stop")) {
              return mockStop;
            }
            return mockLock;
          }
        };

    service.cleanupLock(null);

    UploadId id = new UploadId("test-id");
    service.cleanupLock(id);
    Mockito.verify(mockStop).deleteIfExists();
  }
}
