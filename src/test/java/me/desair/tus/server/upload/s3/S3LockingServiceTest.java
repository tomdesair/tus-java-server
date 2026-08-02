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
}
