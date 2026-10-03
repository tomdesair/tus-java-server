package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.ComposeObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.exception.MinUploadLengthNotReachedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import org.junit.Before;
import org.junit.Test;

public class S3StorageServiceTest {

  private MinioClient minioClient;
  private S3StorageService storageService;

  @Before
  public void setUp() {
    minioClient = mock(MinioClient.class);
    storageService = new S3StorageService(minioClient, "test-bucket");
  }

  @Test
  public void testCreateUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1024L);

    UploadInfo created = storageService.create(info, "owner-1");

    assertNotNull(created);
    assertEquals("uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e", created.getStorageUploadId());
    assertEquals("owner-1", created.getOwnerKey());
    assertEquals(
        "uploads/24249a5b-01a4-4bf8-b67a-364273bb5a2e", storageService.getS3ObjectKey(created));
  }

  @Test
  public void testNullTemporaryDirectoryConstructor() {
    java.nio.file.Path nullPath = null;
    S3StorageService serviceWithNullTmp =
        new S3StorageService(
            minioClient, "test-bucket", "uploads/", "uploads/", "checksums/", "locks/", nullPath);
    assertNotNull(serviceWithNullTmp);
  }

  @Test
  public void testExplicitConnectionParametersConstructors() {
    // 1. Constructor taking connection parameters directly (Option A)
    S3StorageService serviceWithParams =
        new S3StorageService(
            "https://s3.amazonaws.com", "us-east-1", "accessKey", "secretKey", "test-bucket");
    assertNotNull(serviceWithParams);

    // 2. Constructor taking MinioClient and connection parameters (Option B)
    S3StorageService serviceWithClientAndParams =
        new S3StorageService(
            minioClient,
            "https://s3.amazonaws.com",
            "us-east-1",
            "accessKey",
            "secretKey",
            "test-bucket");
    assertNotNull(serviceWithClientAndParams);
  }

  @Test
  public void testGetS3ObjectKeyByUri() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setStorageUploadId("uploads/custom-key-123");
    info.setOwnerKey("owner-1");

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());

    String keyByUri =
        storageService.getS3ObjectKey("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    // Upload belongs to specific owner, so without owner key it should return null
    assertNull(keyByUri);

    String keyByUriAndOwner =
        storageService.getS3ObjectKey(
            "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", "owner-1");
    assertEquals("uploads/custom-key-123", keyByUriAndOwner);

    String keyByUriAndOtherOwner =
        storageService.getS3ObjectKey(
            "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", "owner-2");
    assertNull(keyByUriAndOtherOwner);
  }

  @Test
  public void testGetS3ObjectKeyByUriExceptions() throws Exception {
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("MinIO failure"));

    storageService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());

    assertNull(storageService.getS3ObjectKey("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertNull(
        storageService.getS3ObjectKey(
            "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", "owner-1"));
  }

  @Test(expected = me.desair.tus.server.exception.UploadNotFoundException.class)
  public void testGetUploadedBytesNotFound() throws Exception {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    storageService.getUploadedBytes(new UploadId("missing-123"));
  }

  @Test
  public void testGetUploadedBytesDuplicate() throws Exception {
    UploadInfo child = new UploadInfo();
    child.setId(new UploadId("child-123"));
    child.setDuplicatesUploadId(new UploadId("parent-456"));

    UploadInfo parent = new UploadInfo();
    parent.setId(new UploadId("parent-456"));

    String childJson = UploadInfoJsonSerializer.serialize(child);
    String parentJson = UploadInfoJsonSerializer.serialize(parent);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().contains("child-123")) {
                return mockGetObjectResponse(childJson.getBytes());
              }
              if (args.object().contains("parent-456.info")) {
                return mockGetObjectResponse(parentJson.getBytes());
              }
              return mockGetObjectResponse("parent-data".getBytes());
            });

    InputStream stream = storageService.getUploadedBytes(new UploadId("child-123"));
    assertNotNull(stream);
  }

  @Test
  public void testGetUploadedBytesConcatenatedUnmerged() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("concat-123"));
    info.setUploadType(me.desair.tus.server.upload.UploadType.CONCATENATED);
    info.setStorageUploadId(null);

    UploadInfo mergedInfo = new UploadInfo();
    mergedInfo.setId(new UploadId("concat-123"));
    mergedInfo.setStorageUploadId("uploads/concat-123");

    String jsonBefore = UploadInfoJsonSerializer.serialize(info);
    String jsonAfter = UploadInfoJsonSerializer.serialize(mergedInfo);

    java.util.concurrent.atomic.AtomicInteger infoCallCount =
        new java.util.concurrent.atomic.AtomicInteger();

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                if (infoCallCount.getAndIncrement() == 0) {
                  return mockGetObjectResponse(jsonBefore.getBytes());
                }
                return mockGetObjectResponse(jsonAfter.getBytes());
              }
              return mockGetObjectResponse("merged-bytes".getBytes());
            });

    S3ConcatenationService mockConcat = mock(S3ConcatenationService.class);
    storageService.setUploadConcatenationService(mockConcat);

    InputStream stream = storageService.getUploadedBytes(new UploadId("concat-123"));
    assertNotNull(stream);
  }

  @Test(expected = me.desair.tus.server.exception.MaxAppendSizeExceededException.class)
  public void testAppendExceedsMaxAppendSizeLimit() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(10000L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.setMaxAppendSize(50L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test
  public void testAppendExceedsMaxAppendSizePreservesIncompletePartAndUpdatesOffset()
      throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId uploadId = new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    info.setId(uploadId);
    info.setLength(10000L);
    info.setOffset(20L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              // Simulate existing .part buffer object of 20 bytes
              if (args.object().endsWith(".part")) {
                return mockGetObjectResponse(new byte[20]);
              }
              return mockGetObjectResponse(json.getBytes());
            });

    StatObjectResponse partStat = mock(StatObjectResponse.class);
    when(partStat.size()).thenReturn(20L);
    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return partStat;
              }
              throw new ErrorResponseException(
                  new ErrorResponse("NoSuchKey", "Not found", null, null, null, null, null),
                  null,
                  null);
            });

    storageService.setMaxAppendSize(50L);
    try {
      storageService.append(info, new ByteArrayInputStream(new byte[100]));
      fail("Expected MaxAppendSizeExceededException to be thrown");
    } catch (me.desair.tus.server.exception.MaxAppendSizeExceededException e) {
      // Expected
    }

    // Verify that when maxAppendSize was exceeded, the prepended bytes were NOT lost
    // and were flushed back to S3 as an incomplete .part object.
    verify(minioClient, atLeastOnce()).putObject(argThat(args -> args.object().endsWith(".part")));
  }

  @Test(expected = MinUploadLengthNotReachedException.class)
  public void testAppendBelowMinSize() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);

    String json = UploadInfoJsonSerializer.serialize(info);
    GetObjectResponse stream = mockGetObjectResponse(json.getBytes());

    when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(stream);

    storageService.setMinSize(2000L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test(expected = IOException.class)
  public void testAppendThrowsIOExceptionOnStreamError() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    InputStream brokenStream = mock(InputStream.class);
    when(brokenStream.read(any(byte[].class))).thenThrow(new IOException("Read failed"));

    storageService.append(info, brokenStream);
  }

  @Test
  public void testAppendInterruptedSubPart() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              throw new RuntimeException("Unexpected getObject: " + args.object());
            });

    java.util.concurrent.atomic.AtomicBoolean partSaved =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            inv -> {
              PutObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".part")) {
                partSaved.set(true);
              }
              return null;
            });

    StatObjectResponse partStat = mock(StatObjectResponse.class);
    when(partStat.size()).thenReturn(50L);
    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part") && partSaved.get()) {
                return partStat;
              }
              ErrorResponse errorResponse = mock(ErrorResponse.class);
              when(errorResponse.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(errorResponse, null, null);
            });

    byte[] validBytes = "12345678901234567890123456789012345678901234567890".getBytes(); // 50 bytes
    InputStream brokenStream = mock(InputStream.class);
    when(brokenStream.read(
            any(byte[].class),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IOException("Stream interrupted"));

    InputStream sequenceStream =
        new java.io.SequenceInputStream(new ByteArrayInputStream(validBytes), brokenStream);

    try {
      storageService.append(info, sequenceStream);
      org.junit.Assert.fail("Expected IOException to be thrown");
    } catch (IOException e) {
      assertEquals("Stream interrupted", e.getMessage());
    }

    assertEquals(Long.valueOf(50L), info.getOffset());
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.atLeastOnce())
        .putObject(any(PutObjectArgs.class));
  }

  @Test
  public void testAppendInterruptedMultiPart() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(200000000L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              throw new RuntimeException("Unexpected getObject: " + args.object());
            });

    java.util.concurrent.atomic.AtomicBoolean partSaved =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            inv -> {
              PutObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".part")) {
                partSaved.set(true);
              }
              return null;
            });

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("metadata/24249a5b-01a4-4bf8-b67a-364273bb5a2e.part.00001");
    when(item1.size()).thenReturn(50L * 1024 * 1024);

    Result<Item> result1 = new Result<>(item1);
    Iterable<Result<Item>> listResults = Arrays.asList(result1);
    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(listResults);

    StatObjectResponse chunk1Stat = mock(StatObjectResponse.class);
    when(chunk1Stat.size()).thenReturn(50L * 1024 * 1024);

    StatObjectResponse partStat = mock(StatObjectResponse.class);
    when(partStat.size()).thenReturn(100L);
    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().contains(".part.")) {
                return chunk1Stat;
              }
              if (args.object().endsWith(".part") && partSaved.get()) {
                return partStat;
              }
              ErrorResponse errorResponse = mock(ErrorResponse.class);
              when(errorResponse.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(errorResponse, null, null);
            });

    byte[] fiftyMb = new byte[50 * 1024 * 1024];
    byte[] extraBytes = new byte[100];
    InputStream brokenStream = mock(InputStream.class);
    when(brokenStream.read(
            any(byte[].class),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new IOException("Stream interrupted on part 2"));

    InputStream combinedStream =
        new java.io.SequenceInputStream(
            new java.io.SequenceInputStream(
                new ByteArrayInputStream(fiftyMb), new ByteArrayInputStream(extraBytes)),
            brokenStream);

    try {
      storageService.append(info, combinedStream);
      org.junit.Assert.fail("Expected IOException to be thrown");
    } catch (IOException e) {
      assertEquals("Stream interrupted on part 2", e.getMessage());
    }

    assertEquals(Long.valueOf(50L * 1024 * 1024 + 100L), info.getOffset());
  }

  @Test
  public void testGetUploadInfoReturnsNullForMissingKey() throws Exception {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");

    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    UploadInfo result =
        storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertNull(result);
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoThrowsIOExceptionOnGenericException() throws Exception {
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("Storage failure"));

    storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
  }

  @Test
  public void testGetS3ObjectKeyWithDuplicatesUploadId() {
    UploadInfo info = new UploadInfo();
    UploadId childId = new UploadId("child-id");
    UploadId parentId = new UploadId("parent-id");
    info.setId(childId);
    info.setDuplicatesUploadId(parentId);

    assertEquals("uploads/parent-id", storageService.getS3ObjectKey(info));
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoThrowsIOExceptionOnErrorResponseNon404() throws Exception {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("AccessDenied");

    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
  }

  @Test
  public void testCopyUploadToAndRemoveLastBytes() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(100L);
    info.setOffset(100L);

    String json = UploadInfoJsonSerializer.serialize(info);
    byte[] payload = new byte[100];

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(payload);
            });

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storageService.copyUploadTo(info, baos);
    assertEquals(100, baos.size());

    when(minioClient.statObject(any())).thenReturn(mock(StatObjectResponse.class));

    // Verify removeLastNumberOfBytes updates offset
    storageService.removeLastNumberOfBytes(info, 5);
    assertEquals(Long.valueOf(95L), info.getOffset());

    // Test removeLastNumberOfBytes with byteCount <= 0
    storageService.removeLastNumberOfBytes(info, 0);
  }

  @Test
  public void testTruncateIncompletePartPartial() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setOffset(100L);
    info.setLength(1000L);

    byte[] partBytes = new byte[100];
    StatObjectResponse mockHead = mock(StatObjectResponse.class);
    when(mockHead.size()).thenReturn(100L);

    when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mockHead);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(partBytes));

    storageService.removeLastNumberOfBytes(info, 5);
    assertEquals(Long.valueOf(95L), info.getOffset());
  }

  @Test
  public void testCalculateAndSetOffsetWhenCompletedObjectExists() throws Exception {
    String json = "{\"id\":\"24249a5b-01a4-4bf8-b67a-364273bb5a2e\",\"length\":1000}";
    StatObjectResponse mockHead = mock(StatObjectResponse.class);
    when(mockHead.size()).thenReturn(1000L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));
    when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mockHead);

    UploadInfo fetched =
        storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertNotNull(fetched);
  }

  @Test
  public void testGetUploadInfoWithNullOffsetCalculatesOffset() throws Exception {
    String json =
        "{\"id\":\"24249a5b-01a4-4bf8-b67a-364273bb5a2e\",\"length\":1000,\"offset\":null}";
    StatObjectResponse mockHead = mock(StatObjectResponse.class);
    when(mockHead.size()).thenReturn(500L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);

              // Check if objects ends with ".part"
              if (args.object().endsWith(".part")) {
                // Simulate that the part does not exist by throwing a NoSuchKey exception
                ErrorResponse errorResponse = mock(ErrorResponse.class);
                when(errorResponse.code()).thenReturn("NoSuchKey");
                throw new ErrorResponseException(errorResponse, null, null);
              }

              return mockHead;
            });

    UploadInfo fetched =
        storageService.getUploadInfo(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    assertNotNull(fetched);
    assertEquals(Long.valueOf(500L), fetched.getOffset());
  }

  @Test
  public void testAppendCompletingUploadWithLeftoverPart() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(100L);
    info.setOffset(50L);

    String jsonBefore = UploadInfoJsonSerializer.serialize(info);
    info.setOffset(100L);
    String jsonAfter = UploadInfoJsonSerializer.serialize(info);
    info.setOffset(50L);

    byte[] payload = new byte[50];
    java.util.concurrent.atomic.AtomicInteger infoCallCount =
        new java.util.concurrent.atomic.AtomicInteger();

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                if (infoCallCount.getAndIncrement() == 0) {
                  return mockGetObjectResponse(jsonBefore.getBytes());
                }
                return mockGetObjectResponse(jsonAfter.getBytes());
              }
              return mockGetObjectResponse(payload);
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                StatObjectResponse resp = mock(StatObjectResponse.class);
                when(resp.size()).thenReturn(50L);
                return resp;
              }
              ErrorResponse errorResponse = mock(ErrorResponse.class);
              when(errorResponse.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(errorResponse, null, null);
            });

    storageService.append(info, new ByteArrayInputStream(payload));
  }

  @Test
  public void testFetchS3ByteStreamWithOffsetAndLengthRange() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    String json = UploadInfoJsonSerializer.serialize(info);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse("ranged-payload".getBytes());
            });

    InputStream stream = storageService.getUploadedBytes(info.getId());
    assertNotNull(stream);
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoByChecksumThrowsIOExceptionOnErrorResponseNon404() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("AccessDenied");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    storageService.getUploadInfoByChecksum("abc123hash", ChecksumAlgorithm.SHA256);
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoByChecksumThrowsIOExceptionOnGenericException() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("MinIO failure"));

    storageService.getUploadInfoByChecksum("abc123hash", ChecksumAlgorithm.SHA256);
  }

  @Test
  public void testDeduplicationChecksumLookupSelfCleaningWhenParentMissing() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenReturn(mockGetObjectResponse("stale-parent-456".getBytes()));

    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKey = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(noSuchKey);

    UploadInfo match =
        storageService.getUploadInfoByChecksum("stalehash", ChecksumAlgorithm.SHA256);
    assertNull(match);
  }

  @Test
  public void testDeduplicationChecksumLookup() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    UploadInfo parentInfo = new UploadInfo();
    parentInfo.setId(new UploadId("parent-123"));
    parentInfo.setLength(5000L);

    String json = UploadInfoJsonSerializer.serialize(parentInfo);

    java.util.Map<String, byte[]> objectData = new java.util.HashMap<>();
    objectData.put("checksums/sha256/abc123hash", "parent-123".getBytes());
    objectData.put("uploads/checksums/sha256/abc123hash", "parent-123".getBytes());
    objectData.put("uploads/parent-123.info", json.getBytes());

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              byte[] data = objectData.get(args.object());
              if (data != null) {
                return mockGetObjectResponse(data);
              }
              return mockGetObjectResponse(json.getBytes());
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenReturn(mock(StatObjectResponse.class));

    UploadInfo match =
        storageService.getUploadInfoByChecksum("abc123hash", ChecksumAlgorithm.SHA256);
    assertNotNull(match);
    assertEquals(new UploadId("parent-123"), match.getId());
  }

  @Test
  public void testConfigurationSettersAndGetters() {
    storageService.setMaxUploadSize(5000L);
    assertEquals(5000L, storageService.getMaxUploadSize());

    storageService.setMaxAppendSize(3000L);
    assertEquals(Long.valueOf(3000L), storageService.getMaxAppendSize());

    storageService.setMinAppendSize(100L);
    assertEquals(Long.valueOf(100L), storageService.getMinAppendSize());

    storageService.setMinSize(50L);
    assertEquals(Long.valueOf(50L), storageService.getMinSize());

    storageService.setUploadExpirationPeriod(86400000L);
    assertEquals(Long.valueOf(86400000L), storageService.getUploadExpirationPeriod());

    storageService.setUploadDeduplicationEnabled(true);
    assertTrue(storageService.isUploadDeduplicationEnabled());

    storageService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());

    S3ConcatenationService concat = new S3ConcatenationService(minioClient, "test-bucket");
    storageService.setUploadConcatenationService(concat);
    assertEquals(concat, storageService.getUploadConcatenationService());

    assertNotNull(storageService.getUploadUri());
  }

  @Test
  public void testNullUploadOperations() throws Exception {
    assertNull(storageService.getUploadInfo((UploadId) null));
    assertNull(storageService.getUploadInfo((String) null, null));
    assertNull(storageService.getS3ObjectKey((UploadInfo) null));
    assertNull(storageService.getS3ObjectKey((String) null));

    storageService.update(null);
    storageService.removeLastNumberOfBytes(null, 100);
    storageService.terminateUpload(null);

    assertNull(storageService.getUploadInfoByChecksum(null, null));
    assertNull(storageService.getUploadInfoByChecksum("abc", ChecksumAlgorithm.SHA256));
  }

  @Test(expected = me.desair.tus.server.exception.MinAppendSizeNotMetException.class)
  public void testAppendThrowsMinAppendSizeNotMetException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(1000L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.setMinAppendSize(500L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  /**
   * §4.1.4: "This limit does not apply to upload creation requests with no content, or to requests
   * completing the upload by including the Upload-Complete: ?1 header field."
   */
  @Test
  public void testAppendCompletingUploadBypassesMinAppendSize() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(100L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.setMinAppendSize(500L);
    UploadInfo result = storageService.append(info, new ByteArrayInputStream(new byte[100]));
    assertEquals(Long.valueOf(100L), result.getOffset());
    assertFalse(result.isUploadInProgress());
  }

  @Test(expected = me.desair.tus.server.exception.MaxUploadLengthExceededException.class)
  public void testAppendThrowsMaxUploadLengthExceededException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setLength(2000L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.setMaxUploadSize(1000L);
    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test(expected = me.desair.tus.server.exception.UploadNotFoundException.class)
  public void testGetUploadedBytesByUriNotFoundThrowsException() throws Exception {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    storageService.getUploadedBytes("/files/upload/non-existent-id", null);
  }

  @Test(expected = me.desair.tus.server.exception.UploadNotFoundException.class)
  public void testAppendByUploadIdNotFoundThrowsException() throws Exception {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("non-existent-id"));

    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test(expected = me.desair.tus.server.exception.UploadNotFoundException.class)
  public void testCopyUploadToNotFoundThrowsUploadNotFoundException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e"));
    info.setOffset(100L);

    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, null, null);
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storageService.copyUploadTo(info, baos);
  }

  @Test
  public void testCleanupExpiredUploads() throws Exception {
    UploadInfo expiredInfo = new UploadInfo();
    UploadId expiredId = new UploadId("expired-123");
    expiredInfo.setId(expiredId);
    expiredInfo.setExpirationTimestamp(System.currentTimeMillis() - 10000L);

    String json = UploadInfoJsonSerializer.serialize(expiredInfo);

    Item item = mock(Item.class);
    when(item.objectName()).thenReturn("uploads/expired-123.info");
    Result<Item> result = new Result<>(item);
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(java.util.Collections.singletonList(result));

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    UploadLockingService mockLocking = mock(UploadLockingService.class);
    when(mockLocking.isLocked(expiredId)).thenReturn(false);

    storageService.cleanupExpiredUploads(mockLocking);
  }

  @Test(expected = IOException.class)
  public void testCleanupExpiredUploadsThrowsIOExceptionOnMinioFailure() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("MinIO failure"));

    storageService.cleanupExpiredUploads(null);
  }

  @Test
  public void testFinalizeCompletedUploadWithMultipleParts() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("multi-part-123");
    info.setId(id);
    info.setLength(100L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/multi-part-123.part.00001");
    Item item2 = mock(Item.class);
    when(item2.objectName()).thenReturn("uploads/multi-part-123.part.00002");

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1), new Result<>(item2)));

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.append(info, new ByteArrayInputStream(new byte[100]));
  }

  @Test
  public void testFinalizeCompletedUploadWithLeftoverIncompletePart() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("leftover-part-123");
    info.setId(id);
    info.setLength(50L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    StatObjectResponse leftoverHead = mock(StatObjectResponse.class);
    when(leftoverHead.size()).thenReturn(50L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return leftoverHead;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[50]);
            });

    storageService.append(info, new ByteArrayInputStream(new byte[50]));
  }

  @Test
  public void testFinalizeCompletedUploadWithInconsistentLeftoverPartPurged() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("inconsistent-part-123");
    info.setId(id);
    info.setLength(100L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    StatObjectResponse leftoverHead = mock(StatObjectResponse.class);
    // Leftover is 30 bytes, but total length is 100 bytes (0 parts + 30 != 100) -> Case 3
    when(leftoverHead.size()).thenReturn(30L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return leftoverHead;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[100]);
            });

    storageService.append(info, new ByteArrayInputStream(new byte[100]));

    // Verify inconsistent .part buffer was deleted
    verify(minioClient, atLeastOnce())
        .removeObject(
            argThat(
                (RemoveObjectArgs args) ->
                    args.object().equals("metadata/inconsistent-part-123.part")));
  }

  @Test(expected = IOException.class)
  public void testFinalizeCompletedUploadLeftoverPartPromotionExceptionThrowsIOException()
      throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("leftover-promo-err-123");
    info.setId(id);
    info.setLength(50L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    StatObjectResponse leftoverHead = mock(StatObjectResponse.class);
    when(leftoverHead.size()).thenReturn(50L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return leftoverHead;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              throw new IOException("Simulated network failure streaming leftover part");
            });

    storageService.append(info, new ByteArrayInputStream(new byte[50]));
  }

  @Test
  public void testFinalizeCompletedUploadZeroLength() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("zero-len-123");
    info.setId(id);
    info.setLength(0L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    storageService.append(info, new ByteArrayInputStream(new byte[0]));
  }

  @Test
  public void testTruncateFromCompletedObject() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("trunc-completed-123");
    info.setId(id);
    info.setLength(100L);
    info.setOffset(100L);

    StatObjectResponse mockHead = mock(StatObjectResponse.class);
    when(mockHead.size()).thenReturn(100L);
    when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(mockHead);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(new byte[100]));

    storageService.removeLastNumberOfBytes(info, 30L);
    assertEquals(Long.valueOf(70L), info.getOffset());
  }

  @Test
  public void testTruncateFromIncompletePartByteCountGreaterThanPartSize() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("trunc-inc-123");
    info.setId(id);
    info.setOffset(50L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    StatObjectResponse partHead = mock(StatObjectResponse.class);
    when(partHead.size()).thenReturn(50L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return partHead;
              }
              throw noSuchKeyEx;
            });

    storageService.removeLastNumberOfBytes(info, 100L);
    assertEquals(Long.valueOf(0L), info.getOffset());
  }

  @Test
  public void testTruncateFromIncompletePartPartialBytes() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("trunc-part-123");
    info.setId(id);
    info.setOffset(100L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    StatObjectResponse partHead = mock(StatObjectResponse.class);
    when(partHead.size()).thenReturn(100L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return partHead;
              }
              throw noSuchKeyEx;
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(new byte[100]));

    storageService.removeLastNumberOfBytes(info, 30L);
    assertEquals(Long.valueOf(70L), info.getOffset());
  }

  @Test
  public void testTerminateUploadWithChecksumAndParts() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("term-123");
    info.setId(id);
    info.setChecksum("hash123");
    info.setChecksumAlgorithm(ChecksumAlgorithm.SHA256);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/term-123.part.00001");
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(java.util.Collections.singletonList(new Result<>(item1)));

    storageService.terminateUpload(info);
  }

  @Test
  public void testFetchS3ByteStreamIncompletePartFallback() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("fallback-123");
    info.setId(id);
    info.setOffset(50L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              if (args.object().endsWith(".part")) {
                return mockGetObjectResponse("part-data".getBytes());
              }
              throw noSuchKeyEx;
            });

    InputStream stream = storageService.getUploadedBytes(id);
    assertNotNull(stream);
  }

  @Test
  public void testFetchS3ByteStreamZeroOffsetFallback() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("zero-offset-123");
    info.setId(id);
    info.setOffset(0L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw noSuchKeyEx;
            });

    InputStream stream = storageService.getUploadedBytes(id);
    assertNotNull(stream);
    assertEquals(0, stream.available());
  }

  @Test
  public void testPutChecksumIndexAndObjectExistsExceptions() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("chk-123"));
    info.setLength(100L);
    info.setOffset(100L);
    info.setChecksum("hashabc");
    info.setChecksumAlgorithm(ChecksumAlgorithm.SHA256);

    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              PutObjectArgs args = invocation.getArgument(0);
              if (args.object().contains("checksums")) {
                throw new RuntimeException("Checksum put failure");
              }
              return null;
            });

    storageService.update(info);
  }

  @Test(expected = IOException.class)
  public void testUpdateThrowsIOExceptionOnMinioFailure() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("upd-err-123"));
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("PutObject failure"));
    storageService.update(info);
  }

  @Test
  public void testCreateWhenUpdateThrowsUploadNotFoundException() throws Exception {
    S3StorageService spyService = org.mockito.Mockito.spy(storageService);
    UploadInfo info = new UploadInfo();
    org.mockito.Mockito.doThrow(
            new me.desair.tus.server.exception.UploadNotFoundException("Not found"))
        .when(spyService)
        .update(any(UploadInfo.class));
    UploadInfo created = spyService.create(info, "owner");
    assertNotNull(created);
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoByChecksumWithNonNoSuchKeyError() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);
    ErrorResponse err = mock(ErrorResponse.class);
    when(err.code()).thenReturn("AccessDenied");
    ErrorResponseException ex = new ErrorResponseException(err, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(ex);

    storageService.getUploadInfoByChecksum("hash", ChecksumAlgorithm.SHA1);
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoByChecksumWithGenericException() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("MinIO error"));

    storageService.getUploadInfoByChecksum("hash", ChecksumAlgorithm.SHA1);
  }

  @Test
  public void testPrepareStreamWithExistingIncompletePartGenericException() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("prep-err-123");
    info.setId(id);
    info.setLength(100L);
    info.setOffset(0L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject error");
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenThrow(new RuntimeException("Stat error"));

    ByteArrayInputStream bais = new ByteArrayInputStream(new byte[10]);
    storageService.append(info, bais);
  }

  @Test(expected = IOException.class)
  public void testUploadChunkToS3ThrowsIOException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("chunk-err-123"));
    info.setLength(100L);
    info.setOffset(0L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject error");
            });

    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            inv -> {
              PutObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return null;
              }
              throw new RuntimeException("Chunk put failure");
            });

    storageService.append(info, new ByteArrayInputStream(new byte[10]));
  }

  @Test(expected = IOException.class)
  public void testFinalizeCompletedUploadSinglePartComposeException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("single-compose-err"));
    info.setLength(10L);
    info.setOffset(0L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw noSuchKeyEx;
            });

    StatObjectResponse statRes = mock(StatObjectResponse.class);
    when(statRes.size()).thenReturn(10L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            inv -> {
              StatObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".part")) {
                throw noSuchKeyEx;
              }
              return statRes;
            });

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/single-compose-err.part.00001");
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1)));

    doThrow(new RuntimeException("Compose error"))
        .when(minioClient)
        .composeObject(any(io.minio.ComposeObjectArgs.class));

    storageService.append(info, new ByteArrayInputStream(new byte[10]));
  }

  @Test(expected = IOException.class)
  public void testFinalizeCompletedUploadMultipartComposeException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("multi-compose-err"));
    info.setLength(20L);
    info.setOffset(0L);

    ErrorResponse noSuchKeyErr = mock(ErrorResponse.class);
    when(noSuchKeyErr.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(noSuchKeyErr, null, null);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw noSuchKeyEx;
            });

    StatObjectResponse statRes = mock(StatObjectResponse.class);
    when(statRes.size()).thenReturn(10L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            inv -> {
              StatObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".part")) {
                throw noSuchKeyEx;
              }
              return statRes;
            });

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/multi-compose-err.part.00001");
    Item item2 = mock(Item.class);
    when(item2.objectName()).thenReturn("uploads/multi-compose-err.part.00002");

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1), new Result<>(item2)));

    doThrow(new RuntimeException("Multipart compose error"))
        .when(minioClient)
        .composeObject(any(ComposeObjectArgs.class));

    storageService.append(info, new ByteArrayInputStream(new byte[20]));
  }

  @Test(expected = IOException.class)
  public void testFinalizeCompletedUploadZeroBytePutException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("zero-byte-err"));
    info.setLength(0L);
    info.setOffset(0L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject error");
            });

    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenAnswer(
            inv -> {
              PutObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return null;
              }
              throw new RuntimeException("Zero byte put error");
            });

    storageService.append(info, new ByteArrayInputStream(new byte[0]));
  }

  @Test(expected = me.desair.tus.server.exception.UploadNotFoundException.class)
  public void testFetchS3ByteStreamGenericExceptionOnObjectKey() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("fetch-err-123");
    info.setId(id);
    info.setOffset(10L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject failure");
            });

    storageService.getUploadedBytes(id);
  }

  @Test(expected = IOException.class)
  public void testTruncateFromCompletedObjectThrowsIOException() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("trunc-err-123"));
    info.setLength(20L);
    info.setOffset(20L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject completed object failure");
            });

    storageService.removeLastNumberOfBytes(info, 5L);
  }

  @Test
  public void testTruncateFromIncompletePartExceptionHandling() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("trunc-part-err"));
    info.setLength(20L);
    info.setOffset(10L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenThrow(new RuntimeException("Stat object error"));

    storageService.removeLastNumberOfBytes(info, 5L);
  }

  @Test
  public void testCalculateCurrentOffsetIncompletePartHeadException() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("offset-head-err");
    info.setId(id);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject error");
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenThrow(new RuntimeException("Head error"));

    UploadInfo fetched = storageService.getUploadInfo(id);
  }

  @Test
  public void testFetchExistingPartKeysExceptionIgnored() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("list-err-123"));
    info.setLength(10L);
    info.setOffset(0L);

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            inv -> {
              GetObjectArgs args = inv.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(UploadInfoJsonSerializer.serialize(info).getBytes());
              }
              throw new RuntimeException("GetObject error");
            });

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("List objects error"));

    storageService.append(info, new ByteArrayInputStream(new byte[10]));
  }

  @Test
  public void testCalcOptimalPartSizeForVeryLargeUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("large-upload-123"));
    info.setLength(50000000000L);
    info.setOffset(0L);

    UploadInfo created = storageService.create(info, "owner");
    assertNotNull(created);
  }

  @Test
  public void testDeleteObjectQuietlyNullAndException() throws Exception {
    org.mockito.Mockito.doThrow(new RuntimeException("Remove object error"))
        .when(minioClient)
        .removeObject(any(io.minio.RemoveObjectArgs.class));

    storageService.terminateUpload(null);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("del-err-123"));
    storageService.terminateUpload(info);
  }

  @Test
  public void testSanitizePrefixNullOrEmptyInS3StorageService() throws Exception {
    java.nio.file.Path tmpDir = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));

    S3StorageService s1 = new S3StorageService(minioClient, "bucket", "", "", "", "", tmpDir);
    assertNotNull(s1);

    S3StorageService s2 =
        new S3StorageService(minioClient, "bucket", null, null, null, null, tmpDir);
    assertNotNull(s2);
  }

  @Test
  public void testFinalizeCompletedUploadStreamingReuploadWhenSub5MbIntermediatePart()
      throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("sub5mb-test-123");
    info.setId(id);
    info.setLength(2000L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/sub5mb-test-123.part.00001");
    when(item1.size()).thenReturn(1000L);

    Item item2 = mock(Item.class);
    when(item2.objectName()).thenReturn("uploads/sub5mb-test-123.part.00002");
    when(item2.size()).thenReturn(1000L);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1), new Result<>(item2)));

    StatObjectResponse stat1 = mock(StatObjectResponse.class);
    when(stat1.size()).thenReturn(1000L);
    StatObjectResponse stat2 = mock(StatObjectResponse.class);
    when(stat2.size()).thenReturn(1000L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part.00001")) {
                return stat1;
              } else if (args.object().endsWith(".part.00002")) {
                return stat2;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[1000]);
            });

    UploadInfo result = storageService.append(info, new ByteArrayInputStream(new byte[2000]));
    assertNotNull(result);
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.never())
        .composeObject(any(ComposeObjectArgs.class));
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.atLeastOnce())
        .putObject(any(PutObjectArgs.class));
  }

  @Test
  public void testFinalizeCompletedUploadComposeObjectWhenAllIntermediatePartsAreAtLeast5Mb()
      throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("compose-test-123");
    long fiveMb = 5L * 1024L * 1024L;
    info.setId(id);
    info.setLength(fiveMb + 100L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/compose-test-123.part.00001");
    when(item1.size()).thenReturn(fiveMb);

    Item item2 = mock(Item.class);
    when(item2.objectName()).thenReturn("uploads/compose-test-123.part.00002");
    when(item2.size()).thenReturn(100L);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1), new Result<>(item2)));

    StatObjectResponse stat1 = mock(StatObjectResponse.class);
    when(stat1.size()).thenReturn(fiveMb);
    StatObjectResponse stat2 = mock(StatObjectResponse.class);
    when(stat2.size()).thenReturn(100L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part.00001")) {
                return stat1;
              } else if (args.object().endsWith(".part.00002")) {
                return stat2;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[100]);
            });

    UploadInfo result =
        storageService.append(info, new ByteArrayInputStream(new byte[(int) (fiveMb + 100L)]));
    assertNotNull(result);
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.times(1))
        .composeObject(any(ComposeObjectArgs.class));
  }

  @Test
  public void testFinalizeCompletedUploadComposeObjectFailsFallsBackToStreamingAndDisablesCompose()
      throws Exception {
    assertTrue(storageService.isS3ComposeObjectSupported());

    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("compose-fail-test-123");
    long fiveMb = 5L * 1024L * 1024L;
    info.setId(id);
    info.setLength(fiveMb + 100L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/compose-fail-test-123.part.00001");
    when(item1.size()).thenReturn(fiveMb);

    Item item2 = mock(Item.class);
    when(item2.objectName()).thenReturn("uploads/compose-fail-test-123.part.00002");
    when(item2.size()).thenReturn(100L);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item1), new Result<>(item2)));

    StatObjectResponse stat1 = mock(StatObjectResponse.class);
    when(stat1.size()).thenReturn(fiveMb);
    StatObjectResponse stat2 = mock(StatObjectResponse.class);
    when(stat2.size()).thenReturn(100L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part.00001")) {
                return stat1;
              } else if (args.object().endsWith(".part.00002")) {
                return stat2;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[100]);
            });

    when(minioClient.composeObject(any(ComposeObjectArgs.class)))
        .thenThrow(new RuntimeException("The specified header is not valid in this context"));

    UploadInfo result =
        storageService.append(info, new ByteArrayInputStream(new byte[(int) (fiveMb + 100L)]));
    assertNotNull(result);

    // Verify composeObject was attempted and failed
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.times(1))
        .composeObject(any(ComposeObjectArgs.class));
    // Verify fallback to streaming putObject was executed
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.atLeastOnce())
        .putObject(any(PutObjectArgs.class));
    // Verify s3ComposeObjectSupported is now disabled
    assertFalse(storageService.isS3ComposeObjectSupported());

    // Subsequent upload should directly use streaming without calling composeObject
    UploadInfo info2 = new UploadInfo();
    UploadId id2 = new UploadId("compose-skip-test-456");
    info2.setId(id2);
    info2.setLength(fiveMb + 100L);
    info2.setOffset(0L);

    String json2 = UploadInfoJsonSerializer.serialize(info2);
    Item item21 = mock(Item.class);
    when(item21.objectName()).thenReturn("uploads/compose-skip-test-456.part.00001");
    when(item21.size()).thenReturn(fiveMb);

    Item item22 = mock(Item.class);
    when(item22.objectName()).thenReturn("uploads/compose-skip-test-456.part.00002");
    when(item22.size()).thenReturn(100L);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Arrays.asList(new Result<>(item21), new Result<>(item22)));

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json2.getBytes());
              }
              return mockGetObjectResponse(new byte[100]);
            });

    UploadInfo result2 =
        storageService.append(info2, new ByteArrayInputStream(new byte[(int) (fiveMb + 100L)]));
    assertNotNull(result2);

    // composeObject count should still be 1 (never called for the second upload)
    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.times(1))
        .composeObject(any(ComposeObjectArgs.class));

    // Reset flag for other tests
    storageService.setS3ComposeObjectSupported(true);
    assertTrue(storageService.isS3ComposeObjectSupported());
  }

  @Test
  public void testPrepareStreamRollsBackSub5MbNumberedPart() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("rollback-test-123");
    info.setId(id);
    info.setLength(10000L);
    info.setOffset(500L);

    String json = UploadInfoJsonSerializer.serialize(info);

    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("uploads/rollback-test-123.part.00001");
    when(item1.size()).thenReturn(500L);

    java.util.Set<String> deletedObjects = new java.util.HashSet<>();
    doAnswer(
            invocation -> {
              RemoveObjectArgs args = invocation.getArgument(0);
              deletedObjects.add(args.object());
              return null;
            })
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            invocation -> {
              if (!deletedObjects.contains("uploads/rollback-test-123.part.00001")) {
                return java.util.Collections.singletonList(new Result<>(item1));
              }
              return java.util.Collections.emptyList();
            });

    StatObjectResponse stat1 = mock(StatObjectResponse.class);
    when(stat1.size()).thenReturn(500L);

    StatObjectResponse statPart = mock(StatObjectResponse.class);
    when(statPart.size()).thenReturn(1000L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part.00001")) {
                return stat1;
              } else if (args.object().endsWith(".part")) {
                return statPart;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[500]);
            });

    UploadInfo result = storageService.append(info, new ByteArrayInputStream(new byte[500]));
    assertNotNull(result);
    assertEquals(1000L, result.getOffset().longValue());
  }

  @Test
  public void testAppendWithInterruptedStreamKeepsChunkInPartObject() throws Exception {
    UploadInfo info = new UploadInfo();
    UploadId id = new UploadId("interrupted-test-123");
    info.setId(id);
    info.setLength(10000L);
    info.setOffset(0L);

    String json = UploadInfoJsonSerializer.serialize(info);

    StatObjectResponse partStat = mock(StatObjectResponse.class);
    when(partStat.size()).thenReturn(50L);

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".part")) {
                return partStat;
              }
              ErrorResponse err = mock(ErrorResponse.class);
              when(err.code()).thenReturn("NoSuchKey");
              throw new ErrorResponseException(err, null, null);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(json.getBytes());
              }
              return mockGetObjectResponse(new byte[0]);
            });

    InputStream faultyStream =
        new InputStream() {
          private int count = 0;

          @Override
          public int read() throws IOException {
            if (count++ < 50) {
              return 'x';
            }
            throw new IOException("Simulated network disconnection");
          }
        };

    try {
      storageService.append(info, faultyStream);
    } catch (IOException expected) {
      // Expected exception due to stream disconnection
    }

    assertEquals(50L, info.getOffset().longValue());
  }

  @Test
  public void testCleanupExpiredUploadsPreservesCompletedUpload() throws Exception {
    UploadInfo completedInfo = new UploadInfo();
    UploadId id = new UploadId("completed-123");
    completedInfo.setId(id);
    completedInfo.setLength(100L);
    completedInfo.setOffset(100L);
    completedInfo.setExpirationTimestamp(System.currentTimeMillis() - 10000L);

    String json = UploadInfoJsonSerializer.serialize(completedInfo);

    Item item = mock(Item.class);
    when(item.objectName()).thenReturn("uploads/completed-123.info");
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(java.util.Collections.singletonList(new Result<>(item)));

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(invocation -> mockGetObjectResponse(json.getBytes()));

    UploadLockingService mockLocking = mock(UploadLockingService.class);
    when(mockLocking.isLocked(id)).thenReturn(false);

    storageService.cleanupExpiredUploads(mockLocking);

    org.mockito.Mockito.verify(minioClient, org.mockito.Mockito.never())
        .removeObject(
            org.mockito.Mockito.argThat(
                (io.minio.RemoveObjectArgs args) -> args.object().equals("uploads/completed-123")));
  }

  @Test
  public void testCleanupExpiredUploadsPrunesStaleTempFilesAndChecksumIndices() throws Exception {
    java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("s3-cleanup-test");
    try {
      java.nio.file.Path staleChunk =
          java.nio.file.Files.createFile(tempDir.resolve("tus-s3-chunk-12345.tmp"));
      java.nio.file.Path stalePrep =
          java.nio.file.Files.createFile(tempDir.resolve("tus-s3-prep-67890.tmp"));
      long twoDaysAgo = System.currentTimeMillis() - (48L * 3600L * 1000L);
      staleChunk.toFile().setLastModified(twoDaysAgo);
      stalePrep.toFile().setLastModified(twoDaysAgo);

      S3StorageService customService =
          new S3StorageService(
              minioClient, "test-bucket", "uploads/", "metadata/", "checksums/", "locks/", tempDir);
      customService.setUploadDeduplicationEnabled(true);

      Item checksumItem = mock(Item.class);
      when(checksumItem.isDir()).thenReturn(false);
      when(checksumItem.objectName()).thenReturn("checksums/sha1/abcdef123456");

      when(minioClient.listObjects(any(ListObjectsArgs.class)))
          .thenAnswer(
              invocation -> {
                ListObjectsArgs args = invocation.getArgument(0);
                if (args.prefix().startsWith("checksums/")) {
                  return java.util.Collections.singletonList(new Result<>(checksumItem));
                }
                return java.util.Collections.emptyList();
              });

      when(minioClient.getObject(any(GetObjectArgs.class)))
          .thenAnswer(
              invocation -> {
                GetObjectArgs args = invocation.getArgument(0);
                if (args.object().equals("checksums/sha1/abcdef123456")) {
                  return mockGetObjectResponse("non-existent-parent".getBytes());
                }
                ErrorResponse err = mock(ErrorResponse.class);
                when(err.code()).thenReturn("NoSuchKey");
                throw new ErrorResponseException(err, null, null);
              });

      customService.cleanupExpiredUploads(null);

      assertFalse(java.nio.file.Files.exists(staleChunk));
      assertFalse(java.nio.file.Files.exists(stalePrep));
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(tempDir.toFile());
    }
  }

  @Test
  public void testCloudUploadThreadPoolSizeConfiguration() {
    assertEquals(
        "Default thread pool size is 10", 10, storageService.getCloudUploadThreadPoolSize());

    storageService.setCloudUploadThreadPoolSize(25);
    assertEquals(
        "Updated thread pool size is 25", 25, storageService.getCloudUploadThreadPoolSize());

    storageService.setCloudUploadThreadPoolSize(4);
    assertEquals("Reduced thread pool size is 4", 4, storageService.getCloudUploadThreadPoolSize());

    try {
      storageService.setCloudUploadThreadPoolSize(0);
      fail("Should reject 0 pool size");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("greater than 0"));
    }

    try {
      storageService.setCloudUploadThreadPoolSize(-5);
      fail("Should reject negative pool size");
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

    storageService.setDrainTimeout(Duration.ofSeconds(20));
    assertEquals(
        "Updated drain timeout is 20 seconds",
        Duration.ofSeconds(20),
        storageService.getDrainTimeout());

    // Null is ignored preserving current value
    storageService.setDrainTimeout(null);
    assertEquals(
        "Null drain timeout preserves previous value",
        Duration.ofSeconds(20),
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
  public void testFinalizeUploadWithStaleIncompletePartIgnored() throws Exception {
    UploadId uploadId = new UploadId("stale-part-test");
    UploadInfo info = new UploadInfo();
    info.setId(uploadId);
    info.setOffset(0L);
    info.setLength(3425070L); // 3.42 MB total upload length

    String infoJson = UploadInfoJsonSerializer.serialize(info);
    String partKey1 = "metadata/stale-part-test.part.00001";
    String stalePartKey = "metadata/stale-part-test.part";

    Item part1Item = mock(Item.class);
    when(part1Item.objectName()).thenReturn(partKey1);

    StatObjectResponse part1Stat = mock(StatObjectResponse.class);
    when(part1Stat.size()).thenReturn(3425070L); // Numbered part already covers full 3.42 MB

    StatObjectResponse stalePartStat = mock(StatObjectResponse.class);
    when(stalePartStat.size()).thenReturn(1277952L); // Stale leftover from prior pause

    StatObjectResponse objectNotExists = mock(StatObjectResponse.class);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            invocation -> {
              ListObjectsArgs args = invocation.getArgument(0);
              if (args.prefix().startsWith("metadata/stale-part-test.part.")) {
                return Collections.singletonList(new Result<>(part1Item));
              }
              return Collections.emptyList();
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().equals(partKey1)) {
                return part1Stat;
              } else if (args.object().equals(stalePartKey)) {
                return stalePartStat;
              } else if (args.object().equals("uploads/stale-part-test")) {
                ErrorResponse err = mock(ErrorResponse.class);
                when(err.code()).thenReturn("NoSuchKey");
                throw new ErrorResponseException(err, null, null);
              }
              return objectNotExists;
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(infoJson.getBytes());
              }
              return mockGetObjectResponse(new byte[0]);
            });

    // Append 0 bytes to trigger finalization check
    ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
    UploadInfo result = storageService.append(info, emptyStream);

    assertNotNull(result);
    // Verify that the stale .part buffer was deleted via removeObject and NOT promoted to
    // part.00002
    verify(minioClient, atLeastOnce())
        .removeObject(
            argThat(
                (RemoveObjectArgs args) -> args.object().equals("metadata/stale-part-test.part")));
  }

  @Test
  public void testFinalizeUploadWithLegitimateNewIncompletePartPromoted() throws Exception {
    UploadId uploadId = new UploadId("legit-part-test");
    UploadInfo info = new UploadInfo();
    info.setId(uploadId);
    info.setOffset(0L);
    info.setLength(10000000L); // 10 MB total length

    String infoJson = UploadInfoJsonSerializer.serialize(info);
    String partKey1 = "metadata/legit-part-test.part.00001";
    String legitPartKey = "metadata/legit-part-test.part";

    Item part1Item = mock(Item.class);
    when(part1Item.objectName()).thenReturn(partKey1);

    StatObjectResponse part1Stat = mock(StatObjectResponse.class);
    when(part1Stat.size()).thenReturn(8000000L); // 8 MB numbered part

    StatObjectResponse legitPartStat = mock(StatObjectResponse.class);
    when(legitPartStat.size()).thenReturn(2000000L); // 2 MB tail; 8 MB + 2 MB == 10 MB exact match!

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            invocation -> {
              ListObjectsArgs args = invocation.getArgument(0);
              if (args.prefix().startsWith("metadata/legit-part-test.part.")) {
                return Collections.singletonList(new Result<>(part1Item));
              }
              return Collections.emptyList();
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().equals(partKey1)) {
                return part1Stat;
              } else if (args.object().equals(legitPartKey)) {
                return legitPartStat;
              } else if (args.object().equals("uploads/legit-part-test")) {
                ErrorResponse err = mock(ErrorResponse.class);
                when(err.code()).thenReturn("NoSuchKey");
                throw new ErrorResponseException(err, null, null);
              }
              return mock(StatObjectResponse.class);
            });

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(infoJson.getBytes());
              } else if (args.object().equals(legitPartKey)) {
                return mockGetObjectResponse(new byte[2000000]);
              }
              return mockGetObjectResponse(new byte[0]);
            });

    ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
    UploadInfo result = storageService.append(info, emptyStream);

    assertNotNull(result);
    // Verify that the legitimate 2 MB tail was promoted to part.00002
    verify(minioClient)
        .putObject(
            argThat(
                (PutObjectArgs args) ->
                    args.object().equals("metadata/legit-part-test.part.00002")));
  }

  @Test
  public void testCalculateCurrentOffsetDoesNotDoubleCountOrExceedLength() throws Exception {
    UploadId uploadId = new UploadId("calc-offset-test");
    String infoJson = "{\"id\":\"calc-offset-test\",\"length\":3425070,\"offset\":null}";

    String partKey1 = "metadata/calc-offset-test.part.00001";
    String stalePartKey = "metadata/calc-offset-test.part";

    Item part1Item = mock(Item.class);
    when(part1Item.objectName()).thenReturn(partKey1);

    StatObjectResponse part1Stat = mock(StatObjectResponse.class);
    when(part1Stat.size()).thenReturn(3425070L); // 3.42 MB numbered part

    StatObjectResponse stalePartStat = mock(StatObjectResponse.class);
    when(stalePartStat.size()).thenReturn(1277952L); // Stale 1.27 MB leftover

    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              GetObjectArgs args = invocation.getArgument(0);
              if (args.object().endsWith(".info")) {
                return mockGetObjectResponse(infoJson.getBytes());
              }
              return mockGetObjectResponse(new byte[0]);
            });

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenAnswer(
            invocation -> {
              ListObjectsArgs args = invocation.getArgument(0);
              if (args.prefix().startsWith("metadata/calc-offset-test.part.")) {
                return Collections.singletonList(new Result<>(part1Item));
              }
              return Collections.emptyList();
            });

    when(minioClient.statObject(any(StatObjectArgs.class)))
        .thenAnswer(
            invocation -> {
              StatObjectArgs args = invocation.getArgument(0);
              if (args.object().equals(partKey1)) {
                return part1Stat;
              } else if (args.object().equals(stalePartKey)) {
                return stalePartStat;
              } else if (args.object().equals("uploads/calc-offset-test")) {
                ErrorResponse err = mock(ErrorResponse.class);
                when(err.code()).thenReturn("NoSuchKey");
                throw new ErrorResponseException(err, null, null);
              }
              return mock(StatObjectResponse.class);
            });

    UploadInfo fetched = storageService.getUploadInfo(uploadId);
    assertNotNull(fetched);
    // Must be exactly 3,425,070 bytes (not 4,703,022 bytes with double-counted stale part!)
    assertEquals(Long.valueOf(3425070L), fetched.getOffset());

    // Verify stale .part buffer was deleted on the fly
    verify(minioClient, atLeastOnce())
        .removeObject(
            argThat(
                (RemoveObjectArgs args) -> args.object().equals("metadata/calc-offset-test.part")));
  }

  @Test
  public void testCalcOptimalPartSizeCalculations() {
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalPartSize(null));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalPartSize(0L));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalPartSize(100L));
    assertEquals(8 * 1024 * 1024L, storageService.calcOptimalPartSize(100L * 1024 * 1024));
    assertEquals(
        8 * 1024 * 1024L, storageService.calcOptimalPartSize(10_000L * 8 * 1024 * 1024L - 1));

    long largeLength = 10_000L * 16 * 1024 * 1024L;
    assertEquals((largeLength / 10_000L) + 1, storageService.calcOptimalPartSize(largeLength));

    // For 1 TB, part size auto-scales up so upload fits within 10,000 parts
    long oneTb = 1024L * 1024 * 1024 * 1024L;
    assertEquals((oneTb / 10_000L) + 1, storageService.calcOptimalPartSize(oneTb));

    // For 5 TB (S3 max limit), part size scales up to ~524.3 MB
    long fiveTb = 5L * 1024 * 1024 * 1024 * 1024L;
    assertEquals((fiveTb / 10_000L) + 1, storageService.calcOptimalPartSize(fiveTb));
  }

  @Test
  public void testSetAndGetPreferredPartSize() {
    assertEquals(8 * 1024 * 1024L, storageService.getPreferredPartSize());

    storageService.setPreferredPartSize(16 * 1024 * 1024L);
    assertEquals(16 * 1024 * 1024L, storageService.getPreferredPartSize());

    try {
      storageService.setPreferredPartSize(4 * 1024 * 1024L); // Below 5MB limit
      fail("Should reject part size below 5MB");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Preferred part size must be between"));
    }

    try {
      storageService.setPreferredPartSize(6L * 1024 * 1024 * 1024L); // Above 5GB limit
      fail("Should reject part size above 5GB");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Preferred part size must be between"));
    }
  }

  @Test
  public void testValidateRemainingPartBudget() throws Exception {
    UploadInfo info = new UploadInfo();
    storageService.validateRemainingPartBudget(info, 9999);
    // KISS: 9999 parts is within budget, verifies method completes cleanly

    try {
      storageService.validateRemainingPartBudget(info, 10000);
      fail("Should throw MaxAppendSizeExceededException at 10000 parts");
    } catch (MaxAppendSizeExceededException expected) {
      assertTrue(expected.getMessage().contains("maximum allowed S3 limit of 10000 parts"));
    }

    try {
      storageService.validateRemainingPartBudget(info, 10005);
      fail("Should throw MaxAppendSizeExceededException above 10000 parts");
    } catch (MaxAppendSizeExceededException expected) {
      assertTrue(expected.getMessage().contains("maximum allowed S3 limit of 10000 parts"));
    }
  }

  private GetObjectResponse mockGetObjectResponse(byte[] bytes) {
    return new GetObjectResponse(
        null, "test-bucket", "eu-central-1", "object-key", new ByteArrayInputStream(bytes));
  }
}
