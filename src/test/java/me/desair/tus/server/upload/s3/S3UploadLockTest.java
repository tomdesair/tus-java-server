package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class S3UploadLockTest {

  private MinioClient minioClient;
  private ConcurrentMap<String, InputStream> inputStreamMap;

  @Before
  public void setUp() {
    minioClient = mock(MinioClient.class);
    inputStreamMap = new ConcurrentHashMap<>();
  }

  @Test
  public void testLockGettersReleaseAndRenewLease() throws Exception {
    InputStream mockStream = mock(InputStream.class);
    inputStreamMap.put("/files/upload-1", mockStream);

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    assertEquals("holder-123", lock.getHolderId());
    assertEquals("/files/upload-1", lock.getUploadUri());

    // Explicitly call renewLease() to verify lease renewal
    lock.renewLease();

    lock.release();
    assertNotNull(lock);
  }

  @Test
  public void testRenewLeaseExceptionHandling() throws Exception {
    Mockito.doThrow(new RuntimeException("PutObject failed"))
        .when(minioClient)
        .putObject(any(PutObjectArgs.class));

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lock.renewLease();
  }

  @Test
  public void testLockDeleteQuietlyWithNullKeysAndExceptionHandling() throws Exception {
    Mockito.doThrow(new RuntimeException("Remove failed"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));

    S3UploadLock lockWithNullKeys =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            null,
            null,
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lockWithNullKeys.close();

    S3UploadLock lockWithKeys =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lockWithKeys.close();
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerSkipsWhenHolderMismatch() throws Exception {
    // Simulate remote lock owned by another holder
    S3UploadLock otherLock =
        new S3UploadLock(
            "other-holder",
            "/files/upload-1",
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            60000L,
            System.currentTimeMillis() + 60000L);
    String json = me.desair.tus.server.util.S3UploadLockJsonSerializer.serialize(otherLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "my-holder",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");

    // Must NOT remove object because it belongs to other-holder
    Mockito.verify(minioClient, Mockito.never())
        .removeObject(
            Mockito.argThat(
                args -> args != null && "tus-locks/upload-1.lock".equals(args.object())));
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerDeletesWhenHolderMatches() throws Exception {
    // Simulate remote lock owned by this holder
    S3UploadLock myLock =
        new S3UploadLock(
            "my-holder",
            "/files/upload-1",
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            60000L,
            System.currentTimeMillis() + 60000L);
    String json = me.desair.tus.server.util.S3UploadLockJsonSerializer.serialize(myLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "my-holder",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");

    // Must remove object because it matches my-holder
    Mockito.verify(minioClient)
        .removeObject(
            Mockito.argThat(
                args -> args != null && "tus-locks/upload-1.lock".equals(args.object())));
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerHandlesNoSuchKey() throws Exception {
    io.minio.messages.ErrorResponse errorResponse =
        Mockito.mock(io.minio.messages.ErrorResponse.class);
    Mockito.when(errorResponse.code()).thenReturn("NoSuchKey");
    io.minio.errors.ErrorResponseException ex =
        new io.minio.errors.ErrorResponseException(errorResponse, null, null);

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenThrow(ex);

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "my-holder",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");
  }

  @Test
  public void testCloseHeartbeatExecutorShutdownException() throws Exception {
    java.util.concurrent.ScheduledExecutorService mockExecutor =
        mock(java.util.concurrent.ScheduledExecutorService.class);
    Mockito.doThrow(new RuntimeException("Shutdown error")).when(mockExecutor).shutdownNow();

    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap,
            mockExecutor);

    lock.close();
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerNullChecksAndExceptionHandling() throws Exception {
    S3UploadLock lock =
        new S3UploadLock(
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            "holder-123",
            60000L,
            "/files/upload-1",
            inputStreamMap);

    // Null key check
    lock.deleteS3LockObjectIfOwner(null);

    // Exception during removeObject
    Mockito.doThrow(new RuntimeException("Remove failed"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));
    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");
  }
}
