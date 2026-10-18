package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertNotNull;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import java.io.IOException;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import org.junit.Before;
import org.junit.Test;

/**
 * Offline unit tests for {@link AzureBlobChecksumIndex} verifying parameter validation, prefix
 * normalization, and defensive guard clauses.
 */
public class AzureBlobChecksumIndexTest {

  private BlobContainerClient containerClient;
  private AzureBlobChecksumIndex checksumIndex;
  private ChecksumKey key;
  private UploadId uploadId;

  @Before
  public void setUp() {
    containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .buildClient();
    checksumIndex = new AzureBlobChecksumIndex(containerClient);
    key = ChecksumKey.of("0123456789abcdef", ChecksumAlgorithm.SHA256);
    uploadId = new UploadId("test-parent-id");
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobChecksumIndex(null);
  }

  @Test
  public void testConstructorAndSanitizePrefix() {
    AzureBlobChecksumIndex index1 = new AzureBlobChecksumIndex(containerClient);
    assertNotNull(index1);

    AzureBlobChecksumIndex index2 = new AzureBlobChecksumIndex(containerClient, "custom/");
    assertNotNull(index2);

    AzureBlobChecksumIndex index3 = new AzureBlobChecksumIndex(containerClient, "/custom///");
    assertNotNull(index3);

    AzureBlobChecksumIndex index4 = new AzureBlobChecksumIndex(containerClient, "");
    assertNotNull(index4);

    AzureBlobChecksumIndex index5 = new AzureBlobChecksumIndex(containerClient, null);
    assertNotNull(index5);
  }

  @Test(expected = NullPointerException.class)
  public void tryRegisterParentNullKeyThrows() throws IOException {
    checksumIndex.tryRegisterParent(null, uploadId);
  }

  @Test(expected = NullPointerException.class)
  public void tryRegisterParentNullUploadIdThrows() throws IOException {
    checksumIndex.tryRegisterParent(key, null);
  }

  @Test(expected = NullPointerException.class)
  public void findParentNullKeyThrows() throws IOException {
    checksumIndex.findParent(null);
  }

  @Test(expected = NullPointerException.class)
  public void markParentDeletedNullKeyThrows() throws IOException {
    checksumIndex.markParentDeleted(null);
  }

  @Test(expected = NullPointerException.class)
  public void isParentDeletedNullKeyThrows() throws IOException {
    checksumIndex.isParentDeleted(null);
  }

  @Test(expected = NullPointerException.class)
  public void addChildNullKeyThrows() throws IOException {
    checksumIndex.addChild(null, uploadId);
  }

  @Test(expected = NullPointerException.class)
  public void addChildNullUploadIdThrows() throws IOException {
    checksumIndex.addChild(key, null);
  }

  @Test(expected = NullPointerException.class)
  public void removeChildNullKeyThrows() throws IOException {
    checksumIndex.removeChild(null, uploadId);
  }

  @Test(expected = NullPointerException.class)
  public void removeChildNullUploadIdThrows() throws IOException {
    checksumIndex.removeChild(key, null);
  }

  @Test(expected = NullPointerException.class)
  public void hasChildrenNullKeyThrows() throws IOException {
    checksumIndex.hasChildren(null);
  }

  @Test(expected = NullPointerException.class)
  public void deleteIndexNullKeyThrows() throws IOException {
    checksumIndex.deleteIndex(null);
  }
}
