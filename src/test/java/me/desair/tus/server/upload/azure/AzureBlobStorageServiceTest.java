package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Paths;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.upload.TimeBasedUploadIdFactory;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.Before;
import org.junit.Test;

/**
 * Offline unit tests for {@link AzureBlobStorageService} verifying parameter validation, POJO
 * configuration, prefix sanitization, and defensive guard clauses.
 */
public class AzureBlobStorageServiceTest {

  private BlobContainerClient containerClient;
  private AzureBlobStorageService storageService;

  @Before
  public void setUp() {
    containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .buildClient();
    storageService = new AzureBlobStorageService(containerClient);
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobStorageService(null);
  }

  @Test
  public void constructorCustomPrefixes() {
    AzureBlobStorageService customService =
        new AzureBlobStorageService(
            containerClient,
            "custom-uploads/",
            "custom-metadata/",
            "custom-checksums/",
            "custom-locks/",
            Paths.get(System.getProperty("java.io.tmpdir")));
    assertNotNull(customService);
  }

  @Test
  public void constructorPrefixSanitizationVariants() {
    AzureBlobStorageService service =
        new AzureBlobStorageService(
            containerClient,
            "/leading/upload",
            "metadata/no-trailing",
            null,
            "locks/",
            Paths.get(System.getProperty("java.io.tmpdir")));
    assertNotNull(service);
  }

  @Test
  public void configurationGettersAndSetters() {
    storageService.setMaxAppendSize(500L);
    assertEquals(Long.valueOf(500L), storageService.getMaxAppendSize());

    storageService.setMinAppendSize(100L);
    assertEquals(Long.valueOf(100L), storageService.getMinAppendSize());

    storageService.setPreferredBlockSize(8L * 1024 * 1024);
    assertEquals(8L * 1024 * 1024, storageService.getPreferredBlockSize());

    storageService.setUploadDeduplicationEnabled(true);
    assertTrue(storageService.isUploadDeduplicationEnabled());

    storageService.setUploadExpirationPeriod(3600000L);
    assertEquals(Long.valueOf(3600000L), storageService.getUploadExpirationPeriod());

    TimeBasedUploadIdFactory idFactory = new TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/custom/upload");
    storageService.setIdFactory(idFactory);
    assertEquals("/custom/upload", storageService.getUploadUri());
  }

  @Test(expected = NullPointerException.class)
  public void setIdFactoryShouldThrowOnNull() {
    storageService.setIdFactory(null);
  }

  @Test(expected = IllegalArgumentException.class)
  public void setPreferredBlockSizeTooSmallShouldThrow() {
    storageService.setPreferredBlockSize(1024L); // less than 4MB
  }

  @Test(expected = IllegalArgumentException.class)
  public void setPreferredBlockSizeTooLargeShouldThrow() {
    storageService.setPreferredBlockSize(5000L * 1024 * 1024); // greater than 4000MB
  }

  @Test
  public void getAzureBlobNameShouldReturnBlobName() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("12345"));
    assertEquals("uploads/12345", storageService.getAzureBlobName(info));

    info.setDuplicatesUploadId(new UploadId("parent-999"));
    assertEquals("uploads/parent-999", storageService.getAzureBlobName(info));
  }

  @Test
  public void getAzureBlobNameNullInfoShouldReturnNull() throws Exception {
    assertNull(storageService.getAzureBlobName((UploadInfo) null));
    assertNull(storageService.getAzureBlobName("/test/upload/invalid", "owner1"));
  }

  @Test(expected = NullPointerException.class)
  public void createNullInfoShouldThrow() throws Exception {
    storageService.create(null, "owner1");
  }

  @Test(expected = NullPointerException.class)
  public void updateNullInfoShouldThrow() throws Exception {
    storageService.update(null);
  }

  @Test(expected = NullPointerException.class)
  public void appendNullInfoShouldThrow() throws Exception {
    storageService.append(null, new ByteArrayInputStream("test".getBytes()));
  }

  @Test(expected = NullPointerException.class)
  public void appendNullStreamShouldThrow() throws Exception {
    storageService.append(new UploadInfo(), null);
  }

  @Test(expected = NullPointerException.class)
  public void removeLastNumberOfBytesShouldThrowOnNullInfo() throws Exception {
    storageService.removeLastNumberOfBytes(null, 10L);
  }

  @Test
  public void getUploadedBytesNullIdShouldReturnNull() throws Exception {
    assertNull(storageService.getUploadedBytes((UploadId) null));
  }

  @Test(expected = NullPointerException.class)
  public void copyUploadToNullInfoShouldThrow() throws Exception {
    storageService.copyUploadTo(null, new ByteArrayOutputStream());
  }

  @Test(expected = NullPointerException.class)
  public void copyUploadToNullStreamShouldThrow() throws Exception {
    storageService.copyUploadTo(new UploadInfo(), null);
  }

  @Test
  public void terminateUploadNullInfoShouldDoNothing() throws Exception {
    storageService.terminateUpload(null);
    storageService.terminateUpload(new UploadInfo());
  }

  @Test
  public void getUploadInfoByChecksumDisabledOrNull() throws Exception {
    storageService.setUploadDeduplicationEnabled(false);
    assertNull(storageService.getUploadInfoByChecksum("checksum", ChecksumAlgorithm.MD5));

    storageService.setUploadDeduplicationEnabled(true);
    assertNull(storageService.getUploadInfoByChecksum(null, ChecksumAlgorithm.MD5));
    assertNull(storageService.getUploadInfoByChecksum("checksum", null));
  }
}
