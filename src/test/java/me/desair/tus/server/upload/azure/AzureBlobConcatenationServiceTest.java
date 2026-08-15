package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import org.junit.Before;
import org.junit.Test;

/**
 * Offline unit tests for {@link AzureBlobConcatenationService} verifying parameter validation,
 * prefix sanitization, partial upload extraction, and upload state guards.
 */
public class AzureBlobConcatenationServiceTest {

  private BlobContainerClient containerClient;
  private UploadStorageService storageService;
  private AzureBlobConcatenationService concatenationService;

  @Before
  public void setUp() {
    containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .buildClient();
    storageService = mock(UploadStorageService.class);
    concatenationService = new AzureBlobConcatenationService(containerClient, storageService);
  }

  @Test(expected = NullPointerException.class)
  public void constructorShouldThrowOnNullContainerClient() {
    new AzureBlobConcatenationService(null, storageService);
  }

  @Test
  public void constructorPrefixSanitizationVariants() {
    AzureBlobConcatenationService service1 =
        new AzureBlobConcatenationService(containerClient, null, storageService);
    AzureBlobConcatenationService service2 =
        new AzureBlobConcatenationService(containerClient, "/custom/prefix", storageService);
    AzureBlobConcatenationService service3 =
        new AzureBlobConcatenationService(containerClient, "custom/prefix/", storageService);

    assertNotNull(service1);
    assertNotNull(service2);
    assertNotNull(service3);
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

  @Test
  public void mergeWithEmptyPartIdsListShouldDoNothing() throws Exception {
    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Collections.emptyList());
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
    assertEquals(Long.valueOf(0L), finalInfo.getOffset());
  }

  @Test
  public void mergeShouldDoNothingWhenFinalUploadNotInProgress() throws Exception {
    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setOffset(10L);
    finalInfo.setLength(10L); // upload completed, not in progress
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/some-id"));

    concatenationService.merge(finalInfo);
  }

  @Test
  public void mergeShouldDoNothingWhenPartInfoLengthIsNull() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setId(new UploadId("part-1"));
    part1Info.setLength(null); // length null

    when(storageService.getUploadInfo(any(String.class), any())).thenReturn(part1Info);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-1"));
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
    assertEquals(Long.valueOf(0L), finalInfo.getOffset());
  }

  @Test
  public void mergeShouldDoNothingWhenIncompleteOrExpiredPartials() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setId(new UploadId("part-1"));
    part1Info.setLength(10L);
    part1Info.setOffset(5L); // in progress (offset < length)

    when(storageService.getUploadInfo(any(String.class), any())).thenReturn(part1Info);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-1"));
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
    assertEquals(Long.valueOf(0L), finalInfo.getOffset());
  }

  @Test
  public void getPartialUploadsShouldReturnEmptyOnNullInfo() throws Exception {
    assertTrue(concatenationService.getPartialUploads(null).isEmpty());
  }

  @Test
  public void getPartialUploadsShouldReturnEmptyOnNullPartIds() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setConcatenationPartIds(null);
    assertTrue(concatenationService.getPartialUploads(info).isEmpty());
  }

  @Test
  public void getPartialUploadsShouldReturnList() throws Exception {
    UploadInfo part1 = new UploadInfo();
    part1.setId(new UploadId("part-1"));
    when(storageService.getUploadInfo("/test/upload/part-1", null)).thenReturn(part1);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-1"));

    List<UploadInfo> partials = concatenationService.getPartialUploads(finalInfo);
    assertEquals(1, partials.size());
    assertEquals(part1.getId(), partials.get(0).getId());
  }

  @Test(expected = UploadNotFoundException.class)
  public void getConcatenatedBytesShouldThrowOnNullInfo() throws Exception {
    concatenationService.getConcatenatedBytes(null);
  }

  @Test
  public void getConcatenatedBytesShouldReturnEmptyStreamForInProgressUploadWithIncompletePartials()
      throws Exception {
    UploadInfo part1 = new UploadInfo();
    part1.setId(new UploadId("part-1"));
    part1.setLength(10L);
    part1.setOffset(0L); // incomplete

    when(storageService.getUploadInfo("/test/upload/part-1", null)).thenReturn(part1);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-1"));
    finalInfo.setUploadType(UploadType.CONCATENATED);

    InputStream is = concatenationService.getConcatenatedBytes(finalInfo);
    assertNotNull(is);
    assertEquals(0, is.available());
  }

  @Test(expected = IOException.class)
  public void mergeShouldThrowIOExceptionWhenBlockCountExceedsAzureLimit() throws Exception {
    UploadInfo partInfo = new UploadInfo();
    partInfo.setId(new UploadId("part-1"));
    partInfo.setLength(10L);
    partInfo.setOffset(10L);
    when(storageService.getUploadInfo(any(String.class), any())).thenReturn(partInfo);

    List<String> tooManyParts = new ArrayList<>();
    for (int i = 0; i <= 50_000; i++) {
      tooManyParts.add("/test/upload/part-" + i);
    }

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(tooManyParts);
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
  }

  @Test(expected = UploadNotFoundException.class)
  public void getPartialUploadsNotFoundShouldThrow() throws Exception {
    when(storageService.getUploadInfo("/test/upload/part-missing", null)).thenReturn(null);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-missing"));

    concatenationService.getPartialUploads(finalInfo);
  }

  @Test
  public void mergeShouldDoNothingWhenPartialIsExpired() throws Exception {
    UploadInfo part1Info = new UploadInfo();
    part1Info.setId(new UploadId("part-1"));
    part1Info.setLength(10L);
    part1Info.setOffset(10L);
    part1Info.setExpirationTimestamp(System.currentTimeMillis() - 10_000L); // expired

    when(storageService.getUploadInfo(any(String.class), any())).thenReturn(part1Info);

    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Arrays.asList("/test/upload/part-1"));
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
    assertEquals(Long.valueOf(0L), finalInfo.getOffset());
  }

  @Test
  public void mergeShouldDoNothingWhenPartialListIsEmpty() throws Exception {
    UploadInfo finalInfo = new UploadInfo();
    finalInfo.setConcatenationPartIds(Collections.emptyList());
    finalInfo.setUploadType(UploadType.CONCATENATED);

    concatenationService.merge(finalInfo);
    assertEquals(Long.valueOf(0L), finalInfo.getOffset());
  }
}
