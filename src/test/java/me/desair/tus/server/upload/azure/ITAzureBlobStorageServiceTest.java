package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITAzureBlobStorageServiceTest {

  private static GenericContainer<?> azurite;
  private static BlobContainerClient containerClient;
  private static final String CONTAINER = "test-storage-azure-container";

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers Azurite test",
        TestUtils.isContainerRuntimeAvailable());

    azurite = TestUtils.createAzuriteContainer();
    azurite.start();

    containerClient = TestUtils.createBlobContainerClient(azurite, CONTAINER);
  }

  @AfterClass
  public static void tearDownClass() {
    if (azurite != null) {
      azurite.stop();
    }
  }

  @Test
  public void testFullUploadLifecycleOnAzurite() throws Exception {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);
    AzureBlobLockingService locking = new AzureBlobLockingService(containerClient);

    UploadInfo info = new UploadInfo();
    info.setLength(11L);

    UploadInfo created = storage.create(info, "owner1");
    assertNotNull(created.getId());

    try (UploadLock lock = locking.lockUploadByUri("/test/upload/" + created.getId())) {
      assertNotNull(lock);
      storage.append(created, new ByteArrayInputStream("hello ".getBytes()));
      storage.append(created, new ByteArrayInputStream("world".getBytes()));
    }

    UploadInfo fetched = storage.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(11L), fetched.getOffset());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storage.copyUploadTo(fetched, baos);
    assertEquals("hello world", baos.toString());

    String blobName = storage.getAzureBlobName("/test/upload/" + created.getId(), "owner1");
    assertEquals("uploads/" + created.getId(), blobName);
  }

  @Test
  public void testTruncateBytesOnAzurite() throws Exception {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);

    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    UploadInfo created = storage.create(info, "owner1");

    storage.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());

    // Truncate 3 bytes
    storage.removeLastNumberOfBytes(created, 3L);
    assertEquals(Long.valueOf(7L), created.getOffset());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storage.copyUploadTo(created, baos);
    assertEquals("0123456", baos.toString());
  }

  @Test
  public void testDeduplicationOnAzurite() throws Exception {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);
    storage.setUploadDeduplicationEnabled(true);

    // Parent upload
    UploadInfo parent = new UploadInfo();
    parent.setLength(5L);
    parent.setChecksum("5d41402abc4b2a76b9719d911017c592");
    parent.setChecksumAlgorithm(ChecksumAlgorithm.MD5);
    parent = storage.create(parent, "owner1");
    storage.append(parent, new ByteArrayInputStream("hello".getBytes()));

    // Child upload matching parent checksum
    UploadInfo child = new UploadInfo();
    child.setLength(5L);
    child.setChecksum("5d41402abc4b2a76b9719d911017c592");
    child.setChecksumAlgorithm(ChecksumAlgorithm.MD5);
    child = storage.create(child, "owner1");
    storage.append(child, new ByteArrayInputStream("hello".getBytes()));

    assertNotNull(child.getDuplicatesUploadId());
    assertEquals(parent.getId(), child.getDuplicatesUploadId());

    // Clean up parent
    storage.terminateUpload(parent);
    assertNull(
        storage.getUploadInfoByChecksum("5d41402abc4b2a76b9719d911017c592", ChecksumAlgorithm.MD5));
  }
}
