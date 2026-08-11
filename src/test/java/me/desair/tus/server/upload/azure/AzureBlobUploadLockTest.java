package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.specialized.BlobLeaseClient;
import com.azure.storage.blob.specialized.BlobLeaseClientBuilder;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.upload.UploadLock;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class AzureBlobUploadLockTest {

  @ClassRule
  public static GenericContainer<?> azuriteContainer = TestUtils.createAzuriteContainer();

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
}
