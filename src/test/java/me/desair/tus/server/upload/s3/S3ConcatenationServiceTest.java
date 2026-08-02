package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.minio.ComposeObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class S3ConcatenationServiceTest {

  private MinioClient minioClient;
  private UploadStorageService storageService;
  private S3ConcatenationService concatenationService;

  @Before
  public void setUp() {
    minioClient = Mockito.mock(MinioClient.class);
    storageService = Mockito.mock(UploadStorageService.class);
    concatenationService =
        new S3ConcatenationService(
            minioClient,
            "test-bucket",
            "tus-uploads/",
            storageService,
            Paths.get(System.getProperty("java.io.tmpdir")));
  }

  @Test
  public void testConstructorsAndSetters() {
    S3ConcatenationService service1 = new S3ConcatenationService(minioClient, "test-bucket");
    S3ConcatenationService service2 =
        new S3ConcatenationService(minioClient, "test-bucket", storageService);
    service1.setUploadStorageService(storageService);

    assertNotNull(service1);
    assertNotNull(service2);
  }

  @Test
  public void testMergeEarlyReturnConditions() throws Exception {
    // Null upload info
    concatenationService.merge(null);

    // Upload not in progress (offset == length)
    UploadInfo infoNotInProgress = new UploadInfo();
    infoNotInProgress.setLength(100L);
    infoNotInProgress.setOffset(100L);
    infoNotInProgress.setConcatenationPartIds(Arrays.asList("/part-1"));
    concatenationService.merge(infoNotInProgress);

    // Null concatenation part IDs
    UploadInfo infoNullParts = new UploadInfo();
    infoNullParts.setLength(100L);
    infoNullParts.setOffset(50L);
    infoNullParts.setConcatenationPartIds(null);
    concatenationService.merge(infoNullParts);
  }

  @Test
  public void testGetPartialUploads() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);

    UploadInfo p2 = new UploadInfo();
    p2.setId(new UploadId("part-2"));
    p2.setLength(10L * 1024 * 1024);

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);
    Mockito.when(storageService.getUploadInfo("/part-2", "owner-1")).thenReturn(p2);

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1", "/part-2"));

    List<UploadInfo> partials = concatenationService.getPartialUploads(finalUpload);
    assertNotNull(partials);
    assertEquals(2, partials.size());

    // Empty part list
    finalUpload.setConcatenationPartIds(Collections.emptyList());
    assertTrue(concatenationService.getPartialUploads(finalUpload).isEmpty());
  }

  @Test(expected = UploadNotFoundException.class)
  public void testGetPartialUploadsChildNotFound() throws Exception {
    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/missing-part"));

    Mockito.when(storageService.getUploadInfo("/missing-part", "owner-1")).thenReturn(null);

    concatenationService.getPartialUploads(finalUpload);
  }

  @Test
  public void testMergePartialUploadsServerSideCopy() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);
    p1.setOffset(10L * 1024 * 1024);
    p1.setStorageUploadId("custom-part-1-key");

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1"));

    concatenationService.merge(finalUpload);
    assertEquals(Long.valueOf(10L * 1024 * 1024), finalUpload.getLength());
    assertEquals(Long.valueOf(10L * 1024 * 1024), finalUpload.getOffset());
    assertEquals("tus-uploads/final-1", finalUpload.getStorageUploadId());
  }

  @Test
  public void testMergePartialUploadsStreamingReupload() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("small-part-1"));
    p1.setLength(100L); // < 5MB minPartSize
    p1.setOffset(100L);

    Mockito.when(storageService.getUploadInfo("/small-part-1", "owner-1")).thenReturn(p1);
    Mockito.when(storageService.getUploadedBytes(new UploadId("small-part-1")))
        .thenReturn(new ByteArrayInputStream(new byte[100]));

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-streaming"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/small-part-1"));

    concatenationService.merge(finalUpload);
    assertEquals(Long.valueOf(100L), finalUpload.getLength());
  }

  @Test(expected = IOException.class)
  public void testMergeServerSideCopyFails() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);
    p1.setOffset(10L * 1024 * 1024);

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);
    Mockito.when(minioClient.composeObject(Mockito.any(ComposeObjectArgs.class)))
        .thenThrow(new RuntimeException("Compose error"));

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1"));

    concatenationService.merge(finalUpload);
  }

  @Test(expected = IOException.class)
  public void testMergeStreamingReuploadFails() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("small-part-1"));
    p1.setLength(100L);
    p1.setOffset(100L);

    Mockito.when(storageService.getUploadInfo("/small-part-1", "owner-1")).thenReturn(p1);
    Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("PutObject error"));

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-streaming"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/small-part-1"));

    concatenationService.merge(finalUpload);
  }

  @Test
  public void testMergeHandlesStorageServiceUpdateException() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);
    p1.setOffset(10L * 1024 * 1024);

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);
    Mockito.doThrow(new UploadNotFoundException("Not found"))
        .when(storageService)
        .update(Mockito.any(UploadInfo.class));

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1"));

    concatenationService.merge(finalUpload);
  }

  @Test
  public void testGetConcatenatedBytesTriggersMergeWhenStorageUploadIdIsNull() throws Exception {
    UploadInfo p1 = new UploadInfo();
    p1.setId(new UploadId("part-1"));
    p1.setLength(10L * 1024 * 1024);
    p1.setOffset(10L * 1024 * 1024);

    Mockito.when(storageService.getUploadInfo("/part-1", "owner-1")).thenReturn(p1);
    Mockito.when(storageService.getUploadedBytes(new UploadId("final-1")))
        .thenReturn(new ByteArrayInputStream(new byte[10]));

    UploadInfo finalUpload = new UploadInfo();
    finalUpload.setId(new UploadId("final-1"));
    finalUpload.setOwnerKey("owner-1");
    finalUpload.setConcatenationPartIds(Arrays.asList("/part-1"));

    InputStream result = concatenationService.getConcatenatedBytes(finalUpload);
    assertNotNull(result);
  }

  @Test(expected = IOException.class)
  public void testGetConcatenatedBytesWithoutStorageService() throws Exception {
    S3ConcatenationService standalone = new S3ConcatenationService(minioClient, "test-bucket");
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("concat-1"));
    info.setStorageUploadId("tus-uploads/concat-1");

    standalone.getConcatenatedBytes(info);
  }

  @Test
  public void testGetConcatenatedBytesNull() throws Exception {
    assertNull(concatenationService.getConcatenatedBytes(null));
  }
}
