package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteResult;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import me.desair.tus.server.upload.LeaseData;
import me.desair.tus.server.util.InterruptibleInputStream;
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
        .removeObjects(any(RemoveObjectsArgs.class));

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
            "eu-central-1",
            "tus-locks/upload-1.lock",
            new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class))).thenReturn(response);

    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    InterruptibleInputStream stream =
        new InterruptibleInputStream(new ByteArrayInputStream("data".getBytes()));
    inputStreamMap.put("/files/upload-1", stream);

    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.renewLease();

    // On lease ownership loss, renewLease must abort the active input stream immediately
    // to prevent writing un-locked bytes to S3.
    assertTrue(stream.isInterrupted());

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
            "eu-central-1",
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

  @Test
  public void testDoesLockOwnershipMatchNullChecksAndException() throws Exception {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    // Null key returns false
    org.junit.Assert.assertFalse(lock.doesLockOwnershipMatch(null));

    // Null minioClient returns false
    S3UploadLock nullClientLock =
        new S3UploadLock(
            myLeaseData, null, "test-bucket", "tus-locks/upload-1.lock", null, inputStreamMap);
    org.junit.Assert.assertFalse(nullClientLock.doesLockOwnershipMatch("key"));

    // Null bucket returns false
    S3UploadLock nullBucketLock =
        new S3UploadLock(
            myLeaseData, minioClient, null, "tus-locks/upload-1.lock", null, inputStreamMap);
    org.junit.Assert.assertFalse(nullBucketLock.doesLockOwnershipMatch("key"));

    // General exception (non-ErrorResponseException) returns true to allow proceed
    Mockito.when(minioClient.getObject(any(io.minio.GetObjectArgs.class)))
        .thenThrow(new RuntimeException("Transient S3 error"));
    org.junit.Assert.assertTrue(lock.doesLockOwnershipMatch("tus-locks/upload-1.lock"));

    // Renew lease with null lockKey or null minioClient
    nullClientLock.doRenewLease();
    S3UploadLock nullKeyLock =
        new S3UploadLock(myLeaseData, minioClient, "test-bucket", null, null, inputStreamMap);
    nullKeyLock.doRenewLease();
  }

  @Test
  public void testReleaseLockResourceWhenOwnershipMismatch() throws Exception {
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
            "eu-central-1",
            "tus-locks/upload-1.lock",
            new ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

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

    lock.close();

    // Must NOT call removeObjects or removeObject because lock was stolen by another node
    // and any stop signal might have been set by a third thread for the new holder
    Mockito.verify(minioClient, Mockito.never()).removeObjects(any(RemoveObjectsArgs.class));
    Mockito.verify(minioClient, Mockito.never()).removeObject(any(RemoveObjectArgs.class));
  }

  @Test
  public void testDeleteS3ObjectsNullChecksAndEmpty() {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    // Null client
    S3UploadLock nullClientLock =
        new S3UploadLock(
            myLeaseData, null, "test-bucket", "tus-locks/upload-1.lock", null, inputStreamMap);
    nullClientLock.deleteS3Objects("key1", "key2");

    // Null bucket
    S3UploadLock nullBucketLock =
        new S3UploadLock(
            myLeaseData, minioClient, null, "tus-locks/upload-1.lock", null, inputStreamMap);
    nullBucketLock.deleteS3Objects("key1", "key2");

    // Both keys null -> empty objects
    lock.deleteS3Objects(null, null);

    Mockito.verify(minioClient, Mockito.never()).removeObjects(any(RemoveObjectsArgs.class));
    assertEquals("test-bucket", lock.getBucket());
    assertEquals("tus-locks/upload-1.lock", lock.getLockKey());
    assertEquals("tus-locks/upload-1.stop", lock.getStopKey());
  }

  @Test
  public void testDeleteS3ObjectsWithErrorsAndExceptions() throws Exception {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    DeleteResult.Error deleteError = mock(DeleteResult.Error.class);
    Mockito.when(deleteError.objectName()).thenReturn("tus-locks/upload-1.lock");
    Mockito.when(deleteError.message()).thenReturn("Access Denied");

    @SuppressWarnings("unchecked")
    Result<DeleteResult.Error> errorResult = mock(Result.class);
    Mockito.when(errorResult.get()).thenReturn(deleteError);

    @SuppressWarnings("unchecked")
    Result<DeleteResult.Error> throwingResult = mock(Result.class);
    Mockito.when(throwingResult.get()).thenThrow(new RuntimeException("Result parsing error"));

    List<Result<DeleteResult.Error>> resultList = Arrays.asList(errorResult, throwingResult, null);

    Mockito.when(minioClient.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(resultList);

    // Call deleteS3Objects with only firstKey, then with both keys
    lock.deleteS3Objects("tus-locks/upload-1.lock", null);
    lock.deleteS3Objects(null, "tus-locks/upload-1.stop");

    Mockito.verify(minioClient, Mockito.times(2)).removeObjects(any(RemoveObjectsArgs.class));
    assertEquals("my-holder", lock.getHolderId());
  }

  @Test
  public void testDeleteS3ObjectsWhenMinioClientThrows() {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    Mockito.when(minioClient.removeObjects(any(RemoveObjectsArgs.class)))
        .thenThrow(new RuntimeException("S3 connection error"));

    // Should catch exception quietly without propagating
    lock.deleteS3Objects("tus-locks/upload-1.lock", "tus-locks/upload-1.stop");
    assertEquals("my-holder", lock.getHolderId());
  }

  @Test
  public void testDeleteSingleObjectEdgeCases() throws Exception {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    lock.deleteSingleObject(null);

    S3UploadLock nullClientLock =
        new S3UploadLock(myLeaseData, null, "test-bucket", "k1", "k2", inputStreamMap);
    nullClientLock.deleteSingleObject("k1");

    S3UploadLock nullBucketLock =
        new S3UploadLock(myLeaseData, minioClient, null, "k1", "k2", inputStreamMap);
    nullBucketLock.deleteSingleObject("k1");

    Mockito.doThrow(new RuntimeException("removeObject error"))
        .when(minioClient)
        .removeObject(any(RemoveObjectArgs.class));
    lock.deleteSingleObject("tus-locks/upload-1.lock");
    assertEquals("my-holder", lock.getHolderId());
  }

  @Test
  public void testDeleteS3ObjectsResultIteratorExceptionFallback() throws Exception {
    LeaseData myLeaseData = createLeaseData("my-holder", "/files/upload-1");
    S3UploadLock lock =
        new S3UploadLock(
            myLeaseData,
            minioClient,
            "test-bucket",
            "tus-locks/upload-1.lock",
            "tus-locks/upload-1.stop",
            inputStreamMap);

    @SuppressWarnings("unchecked")
    io.minio.Result<io.minio.messages.DeleteResult.Error> mockResult = mock(io.minio.Result.class);
    Mockito.when(mockResult.get())
        .thenThrow(new RuntimeException("Simulated error while reading delete result"));

    Mockito.when(minioClient.removeObjects(any(RemoveObjectsArgs.class)))
        .thenReturn(java.util.Collections.singletonList(mockResult));

    lock.deleteS3Objects("tus-locks/upload-1.lock", "tus-locks/upload-1.stop");

    Mockito.verify(minioClient)
        .removeObject(
            org.mockito.ArgumentMatchers.argThat(
                (RemoveObjectArgs args) -> args.object().equals("tus-locks/upload-1.lock")));
    Mockito.verify(minioClient)
        .removeObject(
            org.mockito.ArgumentMatchers.argThat(
                (RemoveObjectArgs args) -> args.object().equals("tus-locks/upload-1.stop")));
  }
}
