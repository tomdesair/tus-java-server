package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import okhttp3.Headers;
import org.junit.Before;
import org.junit.Test;

/** Offline unit tests for {@link S3ChecksumIndex}. */
public class S3ChecksumIndexTest {

  private MinioClient minioClient;
  private S3ChecksumIndex checksumIndex;
  private ChecksumKey key;
  private UploadId uploadId;

  @Before
  public void setUp() {
    minioClient = mock(MinioClient.class);
    checksumIndex = new S3ChecksumIndex(minioClient, "test-bucket");
    key = ChecksumKey.of("0123456789abcdef", ChecksumAlgorithm.SHA256);
    uploadId = new UploadId("test-parent-id");
  }

  @Test
  public void testConstructorAndSanitizePrefix() {
    S3ChecksumIndex index1 = new S3ChecksumIndex(minioClient, "bucket");
    assertNotNull(index1);

    S3ChecksumIndex index2 = new S3ChecksumIndex(minioClient, "bucket", "custom/");
    assertNotNull(index2);

    S3ChecksumIndex index3 = new S3ChecksumIndex(minioClient, "bucket", "/custom///");
    assertNotNull(index3);

    S3ChecksumIndex index4 = new S3ChecksumIndex(minioClient, "bucket", "");
    assertNotNull(index4);
  }

  private ErrorResponseException createError(String code) {
    return new ErrorResponseException(
        new ErrorResponse(code, "Error message", null, null, null, null, null), null, null);
  }

  @Test
  public void testTryRegisterParentSuccess() throws Exception {
    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(createError("NoSuchKey"));

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertTrue(registered);
    verify(minioClient).putObject(any(PutObjectArgs.class));
  }

  @Test
  public void testTryRegisterParentWhenTombstoned() throws Exception {
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(createError("PreconditionFailed"));

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertFalse(registered);
  }

  @Test
  public void testTryRegisterParentPreconditionFailedCollision() throws Exception {
    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(createError("NoSuchKey"));

    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(createError("PreconditionFailed"));

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertFalse(registered);
  }

  @Test
  public void testFindParentSuccess() throws Exception {
    byte[] data = "found-parent-id\n".getBytes(StandardCharsets.UTF_8);
    GetObjectResponse response =
        new GetObjectResponse(
            Headers.of("Content-Type", "text/plain"),
            "test-bucket",
            "us-east-1",
            "object-name",
            new ByteArrayInputStream(data));
    when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(response);

    UploadId found = checksumIndex.findParent(key);
    assertNotNull(found);
    assertEquals("found-parent-id", found.toString());
  }

  @Test
  public void testFindParentNotFound() throws Exception {
    when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(createError("NoSuchKey"));

    UploadId found = checksumIndex.findParent(key);
    assertNull(found);
  }

  @Test
  public void testMarkParentDeletedAndIsParentDeleted() throws Exception {
    checksumIndex.markParentDeleted(key);
    verify(minioClient).putObject(any(PutObjectArgs.class));
    // Parent marker object must NOT be deleted during tombstoning
    verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));

    StatObjectResponse statResponse = mock(StatObjectResponse.class);
    when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);
    assertTrue(checksumIndex.isParentDeleted(key));
  }

  @Test
  public void testAddAndRemoveChild() throws Exception {
    UploadId childId = new UploadId("child-id-1");
    checksumIndex.addChild(key, childId);
    verify(minioClient).putObject(any(PutObjectArgs.class));

    checksumIndex.removeChild(key, childId);
    verify(minioClient).removeObject(any(RemoveObjectArgs.class));
  }

  @Test
  public void testHasChildren() throws Exception {
    Item item = mock(Item.class);
    Result<Item> result = new Result<>(item);
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Collections.singletonList(result));

    assertTrue(checksumIndex.hasChildren(key));

    when(minioClient.listObjects(any(ListObjectsArgs.class))).thenReturn(Collections.emptyList());
    assertFalse(checksumIndex.hasChildren(key));
  }

  @Test
  public void testDeleteIndex() throws Exception {
    Item item = mock(Item.class);
    when(item.objectName()).thenReturn("checksums/sha256/0123456789abcdef/parent");
    Result<Item> result = new Result<>(item);
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Collections.singletonList(result));

    checksumIndex.deleteIndex(key);
    verify(minioClient).removeObjects(any(RemoveObjectsArgs.class));
  }

  @Test
  public void testTryRegisterParentApiNotImplementedFallback() throws Exception {
    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(createError("NoSuchKey"));
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(createError("NotImplemented"))
        .thenReturn(null);

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertTrue(registered);
  }

  @Test
  public void testTryRegisterParentNonCasModeObjectExists() throws Exception {
    // Disable conditional writes
    checksumIndex.setS3ConditionalWritesSupported(false);

    StatObjectResponse statResponse = mock(StatObjectResponse.class);
    when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertFalse(registered);
  }

  @Test
  public void testTryRegisterParentNonCasModeSuccess() throws Exception {
    checksumIndex.setS3ConditionalWritesSupported(false);

    when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(createError("NoSuchKey"));

    boolean registered = checksumIndex.tryRegisterParent(key, uploadId);
    assertTrue(registered);
    verify(minioClient).putObject(any(PutObjectArgs.class));
  }

  @Test(expected = java.io.IOException.class)
  public void testHasChildrenThrowsOnException() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("Simulated S3 error"));
    checksumIndex.hasChildren(key);
  }

  @Test
  public void testDeleteIndexWithErrorsLogged() throws Exception {
    Item item = mock(Item.class);
    when(item.objectName()).thenReturn("checksums/sha256/0123456789abcdef/parent");
    Result<Item> result = new Result<>(item);
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Collections.singletonList(result));

    io.minio.messages.DeleteResult.Error s3Error = mock(io.minio.messages.DeleteResult.Error.class);
    when(s3Error.objectName()).thenReturn("parent");
    when(s3Error.message()).thenReturn("Access Denied");
    Result<io.minio.messages.DeleteResult.Error> errorResult = new Result<>(s3Error);

    when(minioClient.removeObjects(any(RemoveObjectsArgs.class)))
        .thenReturn(Collections.singletonList(errorResult));

    checksumIndex.deleteIndex(key);
    verify(minioClient).removeObjects(any(RemoveObjectsArgs.class));
  }

  @Test(expected = java.io.IOException.class)
  public void testDeleteIndexThrowsOnException() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("Simulated error"));
    checksumIndex.deleteIndex(key);
  }

  @Test
  public void testGetAllIndexedKeysSuccess() throws Exception {
    Item item1 = mock(Item.class);
    when(item1.objectName()).thenReturn("checksums/sha256/0123456789abcdef0123456789abcdef/parent");
    Result<Item> result1 = new Result<>(item1);

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(Collections.singletonList(result1));

    Set<ChecksumKey> keys = checksumIndex.getAllIndexedKeys();
    assertEquals(1, keys.size());
    assertTrue(
        keys.contains(
            ChecksumKey.of("0123456789abcdef0123456789abcdef", ChecksumAlgorithm.SHA256)));
  }

  @Test
  public void testGetAllIndexedKeysWithMalformedAndNonParentObjects() throws Exception {
    Item itemNull = mock(Item.class);
    when(itemNull.objectName()).thenReturn(null);

    Item itemNotParent = mock(Item.class);
    when(itemNotParent.objectName()).thenReturn("checksums/sha256/hash/child-1");

    Item itemInvalidAlgo = mock(Item.class);
    when(itemInvalidAlgo.objectName()).thenReturn("checksums/unknown-algo/hash/parent");

    Item itemMalformedParts = mock(Item.class);
    when(itemMalformedParts.objectName()).thenReturn("checksums/too/many/parts/parent");

    Item itemInvalidHash = mock(Item.class);
    when(itemInvalidHash.objectName()).thenReturn("checksums/sha1/invalid_hex_!@#$/parent");

    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenReturn(
            Arrays.asList(
                new Result<>(itemNull),
                new Result<>(itemNotParent),
                new Result<>(itemInvalidAlgo),
                new Result<>(itemMalformedParts),
                new Result<>(itemInvalidHash)));

    Set<ChecksumKey> keys = checksumIndex.getAllIndexedKeys();
    assertTrue(keys.isEmpty());
  }

  @Test(expected = java.io.IOException.class)
  public void testGetAllIndexedKeysThrowsIOExceptionOnS3Error() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("Simulated error"));
    checksumIndex.getAllIndexedKeys();
  }

  @Test(expected = IOException.class)
  public void testTryRegisterParentOtherErrorResponseThrowsIOException() throws Exception {
    when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(createError("InternalError"));

    checksumIndex.tryRegisterParent(key, uploadId);
  }

  @Test(expected = IOException.class)
  public void testTryRegisterParentGenericExceptionThrowsIOException() throws Exception {
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("Simulated error"));

    checksumIndex.tryRegisterParent(key, uploadId);
  }

  @Test(expected = IOException.class)
  public void testMarkParentDeletedExceptionThrowsIOException() throws Exception {
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("Simulated error"));

    checksumIndex.markParentDeleted(key);
  }

  @Test(expected = IOException.class)
  public void testHasChildrenExceptionThrowsIOException() throws Exception {
    when(minioClient.listObjects(any(ListObjectsArgs.class)))
        .thenThrow(new RuntimeException("Simulated error"));

    checksumIndex.hasChildren(key);
  }
}
