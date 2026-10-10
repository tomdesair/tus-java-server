package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClientBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
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

  private com.azure.storage.blob.BlobContainerClient containerClient;
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

  @Test
  public void getUploadInfoNullIdShouldReturnNull() throws Exception {
    assertNull(storageService.getUploadInfo((UploadId) null));
    assertNull(storageService.getUploadInfo("invalid-uri", "owner1"));
    assertNull(storageService.getUploadInfo(null, "owner1"));
  }

  @Test
  public void constructorEmptyPrefixSanitization() {
    AzureBlobStorageService service =
        new AzureBlobStorageService(
            containerClient, "", "", "", "", Paths.get(System.getProperty("java.io.tmpdir")));
    assertNotNull(service);
  }

  @Test
  public void constructorFailsToCreateBufferDirHandledGracefully() throws Exception {
    Path tempFile = Files.createTempFile("tus-azure-buffer-file", ".tmp");
    try {
      Path uncreatablePath = tempFile.resolve("child-dir");
      AzureBlobStorageService service =
          new AzureBlobStorageService(
              containerClient, "uploads", "metadata", "checksums", "locks", uncreatablePath);
      assertNotNull(service);
    } finally {
      Files.deleteIfExists(tempFile);
    }
  }

  @Test(expected = MaxAppendSizeExceededException.class)
  public void validateRemainingBlockBudgetThrowsWhenLimitReached() throws Exception {
    storageService.validateRemainingBlockBudget(new UploadInfo(), 50_000);
  }

  @Test(expected = MaxAppendSizeExceededException.class)
  public void validateRemainingBlockBudgetThrowsWhenLimitReachedNullUpload() throws Exception {
    storageService.validateRemainingBlockBudget(null, 50_001);
  }

  @Test
  public void validateRemainingBlockBudgetSucceedsWhenWithinBudget() throws Exception {
    storageService.validateRemainingBlockBudget(new UploadInfo(), 49_999);
  }

  @Test
  public void calcOptimalBlockSizeCalculations() {
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalBlockSize(null));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalBlockSize(0L));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalBlockSize(100L));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalBlockSize(100L * 1024 * 1024));
    // When 50000 * 8MB is exceeded, optimal block size scales up
    long largeLength = 50_000L * 16 * 1024 * 1024L;
    assertEquals((largeLength / 50_000L) + 1, storageService.calcOptimalBlockSize(largeLength));
  }

  @Test
  public void testCloudUploadThreadPoolSizeConfiguration() {
    assertEquals(
        "Default thread pool size is 10", 10, storageService.getCloudUploadThreadPoolSize());

    storageService.setCloudUploadThreadPoolSize(30);
    assertEquals(
        "Updated thread pool size is 30", 30, storageService.getCloudUploadThreadPoolSize());

    storageService.setCloudUploadThreadPoolSize(4);
    assertEquals("Reduced thread pool size is 4", 4, storageService.getCloudUploadThreadPoolSize());

    try {
      storageService.setCloudUploadThreadPoolSize(0);
      org.junit.Assert.fail("Should reject 0 pool size");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("greater than 0"));
    }

    try {
      storageService.setCloudUploadThreadPoolSize(-1);
      org.junit.Assert.fail("Should reject negative pool size");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("greater than 0"));
    }
  }

  @Test
  public void testDrainTimeoutConfiguration() {
    assertEquals(
        "Default drain timeout is 55 seconds",
        Duration.ofSeconds(55),
        storageService.getDrainTimeout());

    storageService.setDrainTimeout(Duration.ofSeconds(25));
    assertEquals(
        "Updated drain timeout is 25 seconds",
        Duration.ofSeconds(25),
        storageService.getDrainTimeout());

    // Null is ignored preserving current value
    storageService.setDrainTimeout(null);
    assertEquals(
        "Null drain timeout preserves previous value",
        Duration.ofSeconds(25),
        storageService.getDrainTimeout());
  }

  @Test
  public void testCloseGracefulShutdown() throws Exception {
    // Verify closing storage service cleanly terminates background upload executor without error
    storageService.close();
    // KISS: verifying method executes cleanly without throwing an exception

    // Verify close handles thread interruption gracefully
    Thread.currentThread().interrupt();
    try {
      storageService.close();
    } finally {
      Thread.interrupted(); // Clear interrupted status
    }
  }

  @Test
  public void testGenerateBlockIdEntropyAndFormat() {
    String blockId0 = storageService.generateBlockId(0);
    String blockId1 = storageService.generateBlockId(0);

    assertNotNull(blockId0);
    assertNotNull(blockId1);
    // Two block IDs generated for the same index must have different UUID entropy
    org.junit.Assert.assertNotEquals(blockId0, blockId1);

    byte[] decoded0 = java.util.Base64.getDecoder().decode(blockId0);
    String text0 = new String(decoded0, java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(text0.startsWith("blk-000000-"));
    assertEquals(19, text0.length()); // "blk-000000-" (11) + 8 hex chars = 19
  }
}
