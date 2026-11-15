package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadType;
import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITAzureBlobConcatenationService {

  private static GenericContainer<?> azuriteContainer;

  @BeforeClass
  public static void setUpClass() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
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
  private AzureBlobStorageService storageService;
  private AzureBlobConcatenationService concatenationService;

  @Before
  public void setUp() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable() && azuriteContainer != null);
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "concat-unit-container-" + System.nanoTime());
    storageService = new AzureBlobStorageService(containerClient);
    concatenationService = new AzureBlobConcatenationService(containerClient, storageService);
    storageService.setUploadConcatenationService(concatenationService);
  }

  @After
  public void tearDown() throws Exception {
    if (storageService != null) {
      storageService.close();
    }
  }

  @Test(expected = UploadNotFoundException.class)
  public void mergeShouldThrowWhenPartialUploadNotFound() throws Exception {
    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setId(new UploadId("final-id"));
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/non-existing"));
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
  }

  @Test
  public void mergeShouldStageBlocksAndCommitBlockList() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(10L);
    UploadInfo part1 = storageService.create(part1Info, null);
    storageService.append(part1, new ByteArrayInputStream("part1-data".getBytes()));

    UploadInfo part2Info = new UploadInfo();
    part2Info.setLength(10L);
    UploadInfo part2 = storageService.create(part2Info, null);
    storageService.append(part2, new ByteArrayInputStream("part2-data".getBytes()));

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(
        Arrays.asList("/test/upload/" + part1.getId(), "/test/upload/" + part2.getId()));
    finalInfo.setUploadType(UploadType.CONCATENATED);
    UploadInfo createdFinal = storageService.create(finalInfo, null);

    concatenationService.merge(createdFinal);

    assertEquals(Long.valueOf(20L), createdFinal.getOffset());
    assertEquals(Long.valueOf(20L), createdFinal.getLength());
  }

  @Test
  public void getConcatenationBytesShouldReturnCombinedStream() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(5L);
    UploadInfo part1 = storageService.create(part1Info, null);
    storageService.append(part1, new ByteArrayInputStream("hello".getBytes()));

    UploadInfo part2Info = new UploadInfo();
    part2Info.setLength(6L);
    UploadInfo part2 = storageService.create(part2Info, null);
    storageService.append(part2, new ByteArrayInputStream("-world".getBytes()));

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(
        Arrays.asList("/test/upload/" + part1.getId(), "/test/upload/" + part2.getId()));
    finalInfo.setUploadType(UploadType.CONCATENATED);
    UploadInfo createdFinal = storageService.create(finalInfo, null);

    InputStream is = concatenationService.getConcatenatedBytes(createdFinal);
    assertNotNull(is);
    assertEquals("hello-world", IOUtils.toString(is, StandardCharsets.UTF_8));
  }

  @Test(expected = UploadNotFoundException.class)
  public void getConcatenatedBytesShouldThrowOnNullInfo() throws Exception {
    concatenationService.getConcatenatedBytes(null);
  }

  @Test
  public void getPartialUploadsShouldReturnList() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(5L);
    UploadInfo part1 = storageService.create(part1Info, null);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/" + part1.getId()));

    List<UploadInfo> partials = concatenationService.getPartialUploads(finalInfo);
    assertEquals(1, partials.size());
    assertEquals(part1.getId(), partials.get(0).getId());
  }

  @Test
  public void mergeShouldUpdateExpirationWhenExpirationPeriodIsSet() throws Exception {
    storageService.setUploadExpirationPeriod(5000L);

    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(5L);
    UploadInfo part1 = storageService.create(part1Info, null);
    storageService.append(part1, new ByteArrayInputStream("part1".getBytes()));

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/" + part1.getId()));
    finalInfo.setUploadType(UploadType.CONCATENATED);
    UploadInfo createdFinal = storageService.create(finalInfo, null);

    concatenationService.merge(createdFinal);

    assertNotNull(createdFinal.getExpirationTimestamp());
    assertTrue(createdFinal.getExpirationTimestamp() > System.currentTimeMillis());
  }

  @Test
  public void getConcatenatedBytesShouldReturnBytesForCompletedUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(5L);
    info.setLength(5L); // completed (not in progress)
    UploadInfo created = storageService.create(info, null);
    storageService.append(created, new ByteArrayInputStream("hello".getBytes()));

    InputStream is = concatenationService.getConcatenatedBytes(created);
    assertNotNull(is);
    assertEquals("hello", IOUtils.toString(is, StandardCharsets.UTF_8));
  }

  @Test
  public void mergeShouldFallbackToStreamingWhenStageBlockFromUrlFails() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(10L);
    UploadInfo part1 = storageService.create(part1Info, null);
    storageService.append(part1, new ByteArrayInputStream("part1-data".getBytes()));

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/" + part1.getId()));
    finalInfo.setUploadType(UploadType.CONCATENATED);
    UploadInfo createdFinal = storageService.create(finalInfo, null);

    AzureBlobConcatenationService fallbackService =
        new AzureBlobConcatenationService(containerClient, storageService) {
          @Override
          void stageBlockFromUrl(BlockBlobClient client, String blockId, String sourceUrl) {
            throw new BlobStorageException(
                "Simulated 403", new InMemoryAzureHttpResponse(403), null);
          }
        };

    fallbackService.merge(createdFinal);

    assertEquals(Long.valueOf(10L), createdFinal.getOffset());
    assertEquals(Long.valueOf(10L), createdFinal.getLength());
  }

  @Test(expected = BlobStorageException.class)
  public void mergeShouldRethrowUnexpectedBlobStorageException() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setLength(10L);
    UploadInfo part1 = storageService.create(part1Info, null);
    storageService.append(part1, new ByteArrayInputStream("part1-data".getBytes()));

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/" + part1.getId()));
    finalInfo.setUploadType(UploadType.CONCATENATED);
    UploadInfo createdFinal = storageService.create(finalInfo, null);

    AzureBlobConcatenationService failingService =
        new AzureBlobConcatenationService(containerClient, storageService) {
          @Override
          void stageBlockFromUrl(BlockBlobClient client, String blockId, String sourceUrl) {
            throw new BlobStorageException(
                "Simulated 500 Server Error", new InMemoryAzureHttpResponse(500), null);
          }
        };

    failingService.merge(createdFinal);
  }
}
