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
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.util.LeaseDataJsonSerializer;
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

  private LeaseData createLeaseData(String holderId, String requestUri) {
    return new LeaseData(holderId, requestUri, 60000L, System.currentTimeMillis() + 60000L);
  }

  @Test
  public void testLockGettersReleaseAndRenewLease() throws Exception {
    InputStream mockStream = mock(InputStream.class);
    inputStreamMap.put("/files/upload-1", mockStream);

    LeaseData leaseData = createLeaseData("holder-123", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            leaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    assertEquals("holder-123", lock.getHolderId());
    assertEquals("/files/upload-1", lock.getUploadUri());
    assertEquals("test-bucket", lock.getBucket());
    assertEquals("tus-locks/upload-1.lock", lock.getLockKey());
    assertEquals("tus-locks/upload-1.stop", lock.getStopKey());

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

    LeaseData leaseData = createLeaseData("holder-123", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            leaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.renewLease();
    assertEquals("holder-123", lock.getHolderId());
  }

  @Test
  public void testLockDeleteQuietlyWithNullKeysAndExceptionHandling() throws Exception {
    Mockito.doThrow(new RuntimeException("Remove failed"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));

    LeaseData leaseData = createLeaseData("holder-123", "/files/upload-1");
    S3UploadLock lockWithNullKeys =
        new S3UploadLock(leaseData, minioClient, "test-bucket", null, null, inputStreamMap);

    lockWithNullKeys.close();

    S3UploadLock lockWithKeys =
        new S3UploadLock(
            leaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lockWithKeys.close();
    assertEquals("holder-123", lockWithKeys.getHolderId());
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerSkipsWhenHolderMismatch() throws Exception {
    // Simulate remote lock owned by another holder
    LeaseData otherLock =
        new LeaseData(
            "other-holder",
            "/files/upload-1",
            60000L,
            System.currentTimeMillis() + 60000L,
            System.currentTimeMillis(),
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop");
    String json = LeaseDataJsonSerializer.serialize(otherLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
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
    LeaseData myLock =
        new LeaseData(
            "my-holder",
            "/files/upload-1",
            60000L,
            System.currentTimeMillis() + 60000L,
            System.currentTimeMillis(),
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop");
    String json = LeaseDataJsonSerializer.serialize(myLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
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

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");
    assertEquals("my-holder", lock.getHolderId());
  }

  @Test
  public void testCloseHeartbeatExecutorShutdownException() throws Exception {
    java.util.concurrent.ScheduledExecutorService mockExecutor =
        mock(java.util.concurrent.ScheduledExecutorService.class);
    Mockito.doThrow(new RuntimeException("Shutdown error")).when(mockExecutor).shutdownNow();

    LeaseData myLeaseData = createLeaseData("holder-123", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap,
            mockExecutor);

    lock.close();
    assertEquals("holder-123", lock.getHolderId());
  }

  @Test
  public void testDeleteS3LockObjectIfOwnerNullChecksAndExceptionHandling() throws Exception {
    LeaseData myLeaseData = createLeaseData("holder-123", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    // Null key check
    lock.deleteS3LockObjectIfOwner(null);

    // Exception during removeObject
    Mockito.doThrow(new RuntimeException("Remove failed"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));
    lock.deleteS3LockObjectIfOwner("tus-locks/upload-1.lock");
    assertEquals("holder-123", lock.getHolderId());
  }

  @Test
  public void testRenewLeaseSkipsWhenHolderMismatch() throws Exception {
    LeaseData otherLock =
        new LeaseData(
            "other-holder",
            "/files/upload-1",
            60000L,
            System.currentTimeMillis() + 60000L,
            System.currentTimeMillis(),
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop");
    String json = LeaseDataJsonSerializer.serialize(otherLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.renewLease();

    // Must NOT call putObject because lock is now held by other-holder
    Mockito.verify(minioClient, Mockito.never()).putObject(any(PutObjectArgs.class));
    lock.close();
  }

  @Test
  public void testRenewLeaseSucceedsWhenHolderMatches() throws Exception {
    LeaseData myLock =
        new LeaseData(
            "my-holder",
            "/files/upload-1",
            60000L,
            System.currentTimeMillis() + 60000L,
            System.currentTimeMillis(),
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop");
    String json = LeaseDataJsonSerializer.serialize(myLock);

    io.minio.GetObjectResponse response =
        new io.minio.GetObjectResponse(
            null,
            "test-bucket",
            "us-east-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.renewLease();

    // Must call putObject because holder matches
    Mockito.verify(minioClient).putObject(any(PutObjectArgs.class));
    lock.close();
  }
}
