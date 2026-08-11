package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadType;
import org.junit.Assume;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class AzureBlobConcatenationServiceTest {

  @ClassRule
  public static GenericContainer<?> azuriteContainer = TestUtils.createAzuriteContainer();

  private BlobContainerClient containerClient;
  private AzureBlobStorageService storageService;
  private AzureBlobConcatenationService concatenationService;

  @Before
  public void setUp() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "concat-unit-container-" + System.nanoTime());
    storageService = new AzureBlobStorageService(containerClient);
    concatenationService = new AzureBlobConcatenationService(containerClient, storageService);
    storageService.setUploadConcatenationService(concatenationService);
  }

  @Test
  public void mergeShouldDoNothingIfInfoIsNull() throws Exception {
    concatenationService.merge(null);
  }

  @Test
  public void mergeShouldDoNothingIfConcatFilesIsNull() throws Exception {
    UploadInfo info = new UploadInfo();
    concatenationService.merge(info);
  }

  @Test(expected = UploadNotFoundException.class)
  public void mergeShouldThrowWhenPartialUploadNotFound() throws Exception {
    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setId(new me.desair.tus.server.upload.UploadId("final-id"));
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
    assertEquals(
        "hello-world",
        org.apache.commons.io.IOUtils.toString(is, java.nio.charset.StandardCharsets.UTF_8));
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
  public void getPartialUploadsShouldReturnEmptyOnNullInfo() throws Exception {
    assertTrue(concatenationService.getPartialUploads(null).isEmpty());
  }
}
