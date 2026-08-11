package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class AzureBlobStorageServiceTest {

  @ClassRule
  public static GenericContainer<?> azuriteContainer = TestUtils.createAzuriteContainer();

  private BlobContainerClient containerClient;
  private AzureBlobStorageService storageService;

  @Before
  public void setUp() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "unit-test-container-" + System.nanoTime());
    storageService = new AzureBlobStorageService(containerClient);
  }

  @Test
  public void createShouldSetIdAndSaveInfo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(1000L);

    UploadInfo created = storageService.create(info, "owner1");

    assertNotNull(created.getId());
    assertEquals("owner1", created.getOwnerKey());
    assertEquals(Long.valueOf(0L), created.getOffset());
    assertEquals("uploads/" + created.getId(), created.getStorageUploadId());
  }

  @Test
  public void createZeroLengthUploadShouldCommitEmptyDataBlob() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(0L);

    UploadInfo created = storageService.create(info, "owner1");

    assertNotNull(created.getId());
    assertEquals(Long.valueOf(0L), created.getOffset());
  }

  @Test
  public void getUploadInfoShouldReturnInfo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(500L);
    UploadInfo created = storageService.create(info, "owner1");

    UploadInfo fetched = storageService.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(created.getId(), fetched.getId());
    assertEquals(Long.valueOf(500L), fetched.getLength());
  }

  @Test
  public void getUploadInfoNotFoundShouldReturnNull() throws Exception {
    UploadInfo fetched = storageService.getUploadInfo(new UploadId("non-existing-id"));
    assertNull(fetched);
  }

  @Test
  public void getUploadInfoOwnerIsolation() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(500L);
    UploadInfo created = storageService.create(info, "owner1");

    String url = "/test/upload/" + created.getId();
    assertNull(storageService.getUploadInfo(url, "wrong-owner"));
    assertNotNull(storageService.getUploadInfo(url, "owner1"));
  }

  @Test
  public void maxAppendSizeFallback() {
    storageService.setMaxUploadSize(5000L);
    assertEquals(Long.valueOf(5000L), storageService.getMaxAppendSize());

    storageService.setMaxAppendSize(2000L);
    assertEquals(Long.valueOf(2000L), storageService.getMaxAppendSize());
  }

  @Test(expected = MaxAppendSizeExceededException.class)
  public void appendExceedsMaxAppendSizeShouldThrow() throws Exception {
    storageService.setMaxAppendSize(10L);

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");

    ByteArrayInputStream bais = new ByteArrayInputStream("01234567890123456789".getBytes());
    storageService.append(created, bais);
  }

  @Test
  public void terminateUploadShouldDeleteBlobs() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    info.setChecksum("5d41402abc4b2a76b9719d911017c592");
    info.setChecksumAlgorithm(ChecksumAlgorithm.MD5);

    UploadInfo created = storageService.create(info, "owner1");

    storageService.terminateUpload(created);
    assertNull(storageService.getUploadInfo(created.getId()));
  }

  @Test
  public void getAzureBlobNameShouldReturnBlobName() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    UploadInfo created = storageService.create(info, "owner1");

    String blobName = storageService.getAzureBlobName("/test/upload/" + created.getId(), "owner1");
    assertEquals("uploads/" + created.getId(), blobName);
  }

  @Test
  public void copyUploadToShouldCopyDataToOutputStream() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(9L);
    UploadInfo created = storageService.create(info, "owner1");

    ByteArrayInputStream bais = new ByteArrayInputStream("test-data".getBytes());
    storageService.append(created, bais);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storageService.copyUploadTo(created, baos);

    assertEquals("test-data", baos.toString());
  }

  @Test
  public void getUploadedBytesShouldReturnInputStream() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(9L);
    UploadInfo created = storageService.create(info, "owner1");

    ByteArrayInputStream bais = new ByteArrayInputStream("test-data".getBytes());
    storageService.append(created, bais);

    try (java.io.InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      assertEquals(
          "test-data",
          org.apache.commons.io.IOUtils.toString(is, java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test
  public void removeLastNumberOfBytesPartBlobOnly() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());

    storageService.removeLastNumberOfBytes(created, 3L);
    assertEquals(Long.valueOf(7L), created.getOffset());
  }
}
