package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.util.InterruptibleInputStream;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class S3LockingServiceTest {

  private MinioClient minioClient;
  private S3LockingService lockingService;

  @Before
  public void setUp() {
    minioClient = Mockito.mock(MinioClient.class);
    lockingService = new S3LockingService(minioClient, "test-bucket");
  }

  @Test
  public void testLockUploadByUriSuccessAndLockMethods() throws Exception {
    Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class))).thenReturn(null);

    UploadLock lock =
        lockingService.lockUploadByUri("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    assertNotNull(lock);
    assertEquals("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", lock.getUploadUri());

    if (lock instanceof S3UploadLock) {
      S3UploadLock s3Lock = (S3UploadLock) lock;
      assertNotNull(s3Lock.getHolderId());
      s3Lock.release();
    }

    lock.close();
  }

  @Test
  public void testLockUploadByUriInvalidUri() throws Exception {
    UploadLock lock = lockingService.lockUploadByUri("/invalid-uri");
    assertNull(lock);
  }

  @Test
  public void testIsLocked() throws Exception {
    assertFalse(lockingService.isLocked((UploadId) null));

    // Case 1: Lock object missing -> returns false
    ErrorResponse errorResponse = Mockito.mock(ErrorResponse.class);
    Mockito.when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyEx = new ErrorResponseException(errorResponse, null, null);

    Mockito.when(minioClient.getObject(Mockito.any(GetObjectArgs.class))).thenThrow(noSuchKeyEx);

    UploadId uploadId = new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    assertFalse(lockingService.isLocked(uploadId));

    // Case 2: Active lock object present -> returns true
    long futureExpiry = System.currentTimeMillis() + 600000L;
    String lockJson = "{\"holderId\":\"h1\",\"expiresAt\":" + futureExpiry + "}";

    Mockito.when(minioClient.getObject(Mockito.any(GetObjectArgs.class)))
        .thenAnswer(
            invocation ->
                new GetObjectResponse(
                    null,
                    "test-bucket",
                    "us-east-1",
                    "tus-locks/24249a5b.lock",
                    new ByteArrayInputStream(lockJson.getBytes(StandardCharsets.UTF_8))));

    assertTrue(lockingService.isLocked(uploadId));
  }

  @Test
  public void testIsLockedReturnsFalseOnGenericMinioException() throws Exception {
    Mockito.when(minioClient.getObject(Mockito.any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("GetObject failure"));

    UploadId uploadId = new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    assertFalse(lockingService.isLocked(uploadId));
  }

  @Test
  public void testIsLockedReturnsFalseOnErrorResponseNon404() throws Exception {
    ErrorResponse errorResponse = Mockito.mock(ErrorResponse.class);
    Mockito.when(errorResponse.code()).thenReturn("AccessDenied");
    ErrorResponseException accessDeniedEx = new ErrorResponseException(errorResponse, null, null);

    Mockito.when(minioClient.getObject(Mockito.any(GetObjectArgs.class))).thenThrow(accessDeniedEx);

    UploadId uploadId = new UploadId("24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    assertFalse(lockingService.isLocked(uploadId));
  }

  @Test
  public void testLockAcquisitionFailures() throws Exception {
    // Generic Exception during putObject
    Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("S3 unreachable"));

    try {
      lockingService.lockUploadByUri("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    } catch (Exception expected) {
    }
  }

  @Test
  public void testRegisterInputStreamAndRequestLockReleaseWithInterruption() throws Exception {
    lockingService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());

    // Test with InterruptibleInputStream
    InterruptibleInputStream interruptibleStream = Mockito.mock(InterruptibleInputStream.class);

    lockingService.registerInputStream(
        "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", interruptibleStream);

    // Mock statObject to succeed (stop key exists)
    Mockito.when(minioClient.statObject(Mockito.any(StatObjectArgs.class)))
        .thenReturn(Mockito.mock(io.minio.StatObjectResponse.class));

    lockingService.requestLockRelease("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    Mockito.verify(interruptibleStream).interrupt();

    // Test with standard InputStream
    InputStream standardStream = Mockito.mock(InputStream.class);
    lockingService.registerInputStream(
        "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", standardStream);
    lockingService.requestLockRelease("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    Mockito.verify(standardStream).close();

    lockingService.requestLockRelease(null);
  }

  @Test
  public void testCleanupStaleLocksWithExpiredItem() throws Exception {
    Item item = Mockito.mock(Item.class);
    Mockito.when(item.objectName()).thenReturn("tus-locks/expired.lock");

    Result<Item> result = new Result<>(item);
    Iterable<Result<Item>> results = java.util.Collections.singletonList(result);

    Mockito.when(minioClient.listObjects(Mockito.any(ListObjectsArgs.class))).thenReturn(results);

    // Expired lock JSON
    String expiredJson =
        "{\"holderId\":\"h1\",\"expiresAt\":" + (System.currentTimeMillis() - 1000) + "}";
    GetObjectResponse response =
        new GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/expired.lock",
            new ByteArrayInputStream(expiredJson.getBytes(StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(Mockito.any(GetObjectArgs.class))).thenReturn(response);

    lockingService.cleanupStaleLocks();
  }

  @Test(expected = IOException.class)
  public void testCleanupStaleLocksThrowsIOExceptionOnMinioFailure() throws Exception {
    Mockito.when(minioClient.listObjects(Mockito.any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("ListObjects failure"));

    lockingService.cleanupStaleLocks();
  }

  @Test(expected = me.desair.tus.server.exception.UploadAlreadyLockedException.class)
  public void testLockUploadByUriThrowsUploadAlreadyLockedExceptionWhenPutObjectFails()
      throws Exception {
    Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("PutObject failure"));

    lockingService.lockUploadByUri("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
  }

  @Test
  public void testWriteStopSignalHandlesMinioException() throws Exception {
    lockingService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());
    Mockito.when(minioClient.putObject(Mockito.any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("PutObject failure for stop signal"));

    lockingService.requestLockRelease("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
  }

  @Test
  public void testDeleteObjectQuietlyHandlesMinioException() throws Exception {
    Mockito.doThrow(new RuntimeException("RemoveObject failure"))
        .when(minioClient)
        .removeObject(Mockito.any(io.minio.RemoveObjectArgs.class));

    UploadLock lock =
        lockingService.lockUploadByUri("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
    if (lock != null) {
      lock.close();
    }
  }

  @Test
  public void testCheckStopSignalForEntryWithNullUploadId() throws Exception {
    lockingService.setIdFactory(
        new me.desair.tus.server.upload.UploadIdFactory() {
          @Override
          public me.desair.tus.server.upload.UploadId readUploadId(String text) {
            return null;
          }

          @Override
          public me.desair.tus.server.upload.UploadId createId() {
            return null;
          }

          @Override
          public String getUploadUri() {
            return "/";
          }

          @Override
          protected Serializable getIdValueIfValid(String extractedUrlId) {
            throw new UnsupportedOperationException("Unimplemented method 'getIdValueIfValid'");
          }
        });
    InputStream mockStream = Mockito.mock(InputStream.class);
    lockingService.registerInputStream("/invalid-uri", mockStream);
    lockingService.requestLockRelease("/invalid-uri");
  }

  @Test
  public void testInterruptStreamStandardStreamCloseException() throws Exception {
    lockingService.setIdFactory(new me.desair.tus.server.upload.UuidUploadIdFactory());
    InputStream brokenStream = Mockito.mock(InputStream.class);
    Mockito.doThrow(new IOException("Close error")).when(brokenStream).close();

    lockingService.registerInputStream(
        "/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", brokenStream);
    lockingService.requestLockRelease("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e");
  }

  @Test
  public void testSanitizePrefixNullOrEmpty() throws Exception {
    S3LockingService serviceWithEmptyPrefix =
        new S3LockingService(minioClient, "test-bucket", "", 30000L, 0L);
    assertNotNull(serviceWithEmptyPrefix);

    S3LockingService serviceWithNullPrefix =
        new S3LockingService(minioClient, "test-bucket", null, 30000L, 0L);
    assertNotNull(serviceWithNullPrefix);
  }

  @Test
  public void testClose() throws Exception {
    lockingService.close();
  }

  @Test
  public void testCheckStopSignalForEntryExceptionAndNullId() throws Exception {
    lockingService.setIdFactory(new me.desair.tus.server.upload.TimeBasedUploadIdFactory());
    io.minio.MinioClient mockClient = Mockito.mock(io.minio.MinioClient.class);
    Mockito.when(mockClient.statObject(Mockito.any(io.minio.StatObjectArgs.class)))
        .thenThrow(new RuntimeException("General S3 Exception"));
    Mockito.doThrow(new RuntimeException("Remove object failed"))
        .when(mockClient)
        .removeObject(Mockito.any(io.minio.RemoveObjectArgs.class));

    S3LockingService service = new S3LockingService(mockClient, "test-bucket");
    me.desair.tus.server.upload.TimeBasedUploadIdFactory idFactory =
        new me.desair.tus.server.upload.TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/files/upload");
    service.setIdFactory(idFactory);

    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    service.registerInputStream("/files/upload/12345", stream);
    // Triggers checkStopSignalForEntry & deleteObjectQuietly which catch RuntimeException
    service.requestLockRelease("/files/upload/12345");
  }

  @Test
  public void testCheckStopSignalForEntryHappyPath() throws Exception {
    MinioClient mockClient = Mockito.mock(MinioClient.class);
    io.minio.StatObjectResponse mockStat = Mockito.mock(io.minio.StatObjectResponse.class);
    Mockito.when(mockClient.statObject(Mockito.any(StatObjectArgs.class))).thenReturn(mockStat);

    S3LockingService service =
        new S3LockingService(mockClient, "test-bucket", "locks", 30000L, 50L);
    me.desair.tus.server.upload.TimeBasedUploadIdFactory idFactory =
        new me.desair.tus.server.upload.TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/files/upload");
    service.setIdFactory(idFactory);

    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    service.registerInputStream("/files/upload/12345", stream);

    // Wait for background watchdog thread to execute checkStopSignals()
    long deadline = System.currentTimeMillis() + 2000L;
    while (!stream.isInterrupted() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50L);
    }

    assertTrue(stream.isInterrupted());
    Mockito.verify(mockClient, Mockito.atLeastOnce()).statObject(Mockito.any(StatObjectArgs.class));

    service.close();
  }

  @Test
  public void testCheckStopSignalForEntryNoSuchKey() throws Exception {
    MinioClient mockClient = Mockito.mock(MinioClient.class);
    ErrorResponse errorResponse = Mockito.mock(ErrorResponse.class);
    Mockito.when(errorResponse.code()).thenReturn("NoSuchKey");
    ErrorResponseException noSuchKeyException =
        new ErrorResponseException(errorResponse, null, "NoSuchKey");

    Mockito.when(mockClient.statObject(Mockito.any(StatObjectArgs.class)))
        .thenThrow(noSuchKeyException);

    S3LockingService service =
        new S3LockingService(mockClient, "test-bucket", "locks", 30000L, 50L);
    me.desair.tus.server.upload.TimeBasedUploadIdFactory idFactory =
        new me.desair.tus.server.upload.TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/files/upload");
    service.setIdFactory(idFactory);

    ByteArrayInputStream bais = new ByteArrayInputStream("test".getBytes());
    InterruptibleInputStream stream = new InterruptibleInputStream(bais);

    service.registerInputStream("/files/upload/12345", stream);

    // Wait for background watchdog thread to run checkStopSignals()
    Thread.sleep(200L);

    Mockito.verify(mockClient, Mockito.atLeastOnce()).statObject(Mockito.any(StatObjectArgs.class));
    assertFalse(stream.isInterrupted());

    service.close();
  }

  @Test
  public void testCleanupStaleLocksWithExpiredAndNonExpiredLocks() throws Exception {
    MinioClient mockClient = Mockito.mock(MinioClient.class);
    Item expiredItem = Mockito.mock(Item.class);
    Mockito.when(expiredItem.objectName()).thenReturn("locks/expired.lock");

    Item validItem = Mockito.mock(Item.class);
    Mockito.when(validItem.objectName()).thenReturn("locks/valid.lock");

    Result<Item> res1 = new Result<Item>(expiredItem);
    Result<Item> res2 = new Result<Item>(validItem);

    Mockito.when(mockClient.listObjects(Mockito.any(ListObjectsArgs.class)))
        .thenReturn(java.util.Arrays.asList(res1, res2));

    S3UploadLock expiredLock =
        new S3UploadLock(
            "holder1",
            "/files/upload/1",
            "test-bucket",
            "locks/expired.lock",
            "locks/expired.stop",
            30000L,
            1000L);
    S3UploadLock validLock =
        new S3UploadLock(
            "holder2",
            "/files/upload/2",
            "test-bucket",
            "locks/valid.lock",
            "locks/valid.stop",
            30000L,
            System.currentTimeMillis() + 1000000L);

    GetObjectResponse expiredStream =
        new GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "locks/expired.lock",
            new ByteArrayInputStream(
                me.desair.tus.server.util.S3UploadLockJsonSerializer.serialize(expiredLock)
                    .getBytes(StandardCharsets.UTF_8)));
    GetObjectResponse validStream =
        new GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "locks/valid.lock",
            new ByteArrayInputStream(
                me.desair.tus.server.util.S3UploadLockJsonSerializer.serialize(validLock)
                    .getBytes(StandardCharsets.UTF_8)));

    Mockito.when(mockClient.getObject(Mockito.any(GetObjectArgs.class)))
        .thenReturn(expiredStream)
        .thenReturn(validStream);

    S3LockingService service = new S3LockingService(mockClient, "test-bucket");
    service.cleanupStaleLocks();

    // Expired lock object is deleted
    Mockito.verify(mockClient).removeObject(Mockito.any(io.minio.RemoveObjectArgs.class));
  }

  @Test
  public void testRegisterInputStreamNullChecks() {
    lockingService.registerInputStream(null, Mockito.mock(InputStream.class));
    lockingService.registerInputStream("/files/upload/123", null);
  }

  @Test
  public void testWriteStopSignalException() throws Exception {
    MinioClient mockClient = Mockito.mock(MinioClient.class);
    Mockito.when(mockClient.putObject(Mockito.any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("S3 Put error"));

    S3LockingService service = new S3LockingService(mockClient, "test-bucket");
    me.desair.tus.server.upload.TimeBasedUploadIdFactory idFactory =
        new me.desair.tus.server.upload.TimeBasedUploadIdFactory();
    idFactory.setUploadUri("/files/upload");
    service.setIdFactory(idFactory);

    // requestLockRelease calls writeStopSignal which catches RuntimeException
    service.requestLockRelease("/files/upload/12345");
  }

  @Test
  public void testCloseInterruptsActiveStreams() throws Exception {
    MinioClient mockClient = Mockito.mock(MinioClient.class);
    S3LockingService service = new S3LockingService(mockClient, "test-bucket");
    ByteArrayInputStream bis = new ByteArrayInputStream(new byte[] {1, 2, 3});
    InterruptibleInputStream iis = new InterruptibleInputStream(bis);

    service.registerInputStream("/files/upload/24249a5b-01a4-4bf8-b67a-364273bb5a2e", iis);
    org.junit.Assert.assertFalse(iis.isInterrupted());

    service.close();
    org.junit.Assert.assertTrue(iis.isInterrupted());
  }
}
