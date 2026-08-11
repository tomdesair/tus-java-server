package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.upload.UploadLock;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITAzureBlobUploadLock {

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
  private String uploadUri;
  private UploadLock uploadLock;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "lock-unit-container2-" + System.nanoTime());

    uploadUri = "/test/upload/12345";
    BlobClient lockBlob = containerClient.getBlobClient("locks/12345.lock");
    lockBlob.getAppendBlobClient().create();
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
    leaseClient.acquireLease(30);

    uploadLock = new AzureBlobUploadLock(leaseClient, lockBlob, uploadUri);
  }

  @Test
  public void getUploadUriShouldReturnUri() {
    assertEquals(uploadUri, uploadLock.getUploadUri());
  }

  @Test
  public void releaseShouldReleaseLease() {
    uploadLock.release();
  }

  @Test
  public void closeShouldCallRelease() throws Exception {
    uploadLock.close();
  }

  @Test
  public void doubleReleaseShouldBeIdempotent() {
    uploadLock.release();
    uploadLock.release();
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullLeaseClient() {
    BlobClient lockBlob = containerClient.getBlobClient("locks/nulltest.lock");
    new AzureBlobUploadLock(null, lockBlob, "/uri");
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullLockBlob() {
    BlobClient lockBlob = containerClient.getBlobClient("locks/nulltest2.lock");
    lockBlob.getAppendBlobClient().create();
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
    new AzureBlobUploadLock(leaseClient, null, "/uri");
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullUploadUri() {
    BlobClient lockBlob = containerClient.getBlobClient("locks/nulltest3.lock");
    lockBlob.getAppendBlobClient().create();
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
    new AzureBlobUploadLock(leaseClient, lockBlob, null);
  }

  @Test
  public void releaseShouldHandleReleaseLeaseExceptionGracefully() {
    BlobClient lockBlob = containerClient.getBlobClient("locks/releasetest.lock");
    lockBlob.getAppendBlobClient().create();
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
    AzureBlobUploadLock lock = new AzureBlobUploadLock(leaseClient, lockBlob, "/test/uri");
    lock.release();
  }

  @Test
  public void renewLeaseWhenReleasedShouldReturnImmediately() {
    AzureBlobUploadLock lock = (AzureBlobUploadLock) uploadLock;
    lock.release();
    lock.renewLease();
  }

  @Test
  public void renewLeaseShouldRenewActiveLease() {
    AzureBlobUploadLock lock = (AzureBlobUploadLock) uploadLock;
    lock.renewLease();
  }

  @Test
  public void renewLeaseFailureShouldCatchExceptionAndSetReleased() {
    BlobClient lockBlob = containerClient.getBlobClient("locks/renewfail.lock");
    lockBlob.getAppendBlobClient().create();
    BlobLeaseClient leaseClient = new BlobLeaseClientBuilder().blobClient(lockBlob).buildClient();
    AzureBlobUploadLock lock = new AzureBlobUploadLock(leaseClient, lockBlob, "/test/uri");
    lock.renewLease();
    lock.renewLease();
  }
}
