package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteResult;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

public class S3UtilsTest {

  @Test
  public void testBuildMinioClient() {
    MinioClient clientWithRegion =
        S3Utils.buildMinioClient("https://s3.amazonaws.com", "eu-central-1", "access", "secret");
    assertNotNull(clientWithRegion);

    MinioClient clientDefaultRegion =
        S3Utils.buildMinioClient("https://s3.amazonaws.com", null, "access", "secret");
    assertNotNull(clientDefaultRegion);

    MinioClient clientEmptyRegion =
        S3Utils.buildMinioClient("https://s3.amazonaws.com", "", "access", "secret");
    assertNotNull(clientEmptyRegion);
  }

  @Test
  public void testDeleteS3Object() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null checks (should not throw)
    S3Utils.deleteS3Object(null, "bucket", "key");
    S3Utils.deleteS3Object(client, null, "key");
    S3Utils.deleteS3Object(client, "bucket", null);

    // Normal deletion
    S3Utils.deleteS3Object(client, "test-bucket", "test-key");
    verify(client, times(1)).removeObject(any(RemoveObjectArgs.class));

    // Exception propagated
    doThrow(new RuntimeException("Network error"))
        .when(client)
        .removeObject(any(RemoveObjectArgs.class));
    try {
      S3Utils.deleteS3Object(client, "test-bucket", "test-key");
      org.junit.Assert.fail("Expected RuntimeException to be thrown");
    } catch (RuntimeException e) {
      assertEquals("Network error", e.getMessage());
    }
  }

  @Test
  public void testDeleteS3ObjectQuietly() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null checks (should not throw)
    S3Utils.deleteS3ObjectQuietly(null, "bucket", "key");
    S3Utils.deleteS3ObjectQuietly(client, null, "key");
    S3Utils.deleteS3ObjectQuietly(client, "bucket", null);

    // Normal deletion
    S3Utils.deleteS3ObjectQuietly(client, "test-bucket", "test-key");
    verify(client, times(1)).removeObject(any(RemoveObjectArgs.class));

    // Exception handled quietly
    doThrow(new RuntimeException("Network error"))
        .when(client)
        .removeObject(any(RemoveObjectArgs.class));
    S3Utils.deleteS3ObjectQuietly(client, "test-bucket", "test-key");
  }

  @Test
  public void testGetObjectInputStream() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null checks
    assertNull(S3Utils.getObjectInputStream(null, "bucket", "key"));
    assertNull(S3Utils.getObjectInputStream(client, null, "key"));
    assertNull(S3Utils.getObjectInputStream(client, "bucket", null));

    // Successful stream retrieval
    GetObjectResponse mockResponse = mock(GetObjectResponse.class);
    when(client.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

    InputStream resultStream = S3Utils.getObjectInputStream(client, "bucket", "key");
    assertNotNull(resultStream);
    assertEquals(mockResponse, resultStream);

    // Exception propagation
    when(client.getObject(any(GetObjectArgs.class)))
        .thenThrow(new RuntimeException("S3 connection dropped"));
    try {
      S3Utils.getObjectInputStream(client, "bucket", "key");
      org.junit.Assert.fail("Expected RuntimeException to be thrown");
    } catch (RuntimeException e) {
      assertEquals("S3 connection dropped", e.getMessage());
    }
  }

  @Test
  public void testDeleteS3ObjectsQuietly() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null and empty checks (should not throw)
    S3Utils.deleteS3ObjectsQuietly(null, "bucket", Collections.singletonList("key"));
    S3Utils.deleteS3ObjectsQuietly(client, null, Collections.singletonList("key"));
    S3Utils.deleteS3ObjectsQuietly(client, "bucket", null);
    S3Utils.deleteS3ObjectsQuietly(client, "bucket", Collections.emptyList());
    S3Utils.deleteS3ObjectsQuietly(client, "bucket", Collections.singletonList(null));
    S3Utils.deleteS3ObjectsQuietly(client, "bucket", Arrays.asList(" ", "   ", ""));

    // 1. Successful batch deletion (no errors returned by S3)
    when(client.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(Collections.emptyList());
    S3Utils.deleteS3ObjectsQuietly(client, "test-bucket", Arrays.asList("key1", "key2"));
    verify(client, times(1)).removeObjects(any(RemoveObjectsArgs.class));
    verify(client, times(0)).removeObject(any(RemoveObjectArgs.class));

    // 2. Batch deletion reporting error item (falls back to individual deletion)
    DeleteResult.Error mockError = mock(DeleteResult.Error.class);
    when(mockError.objectName()).thenReturn("key1");
    when(mockError.message()).thenReturn("AccessDenied");
    Result<DeleteResult.Error> result = new Result<>(mockError);
    List<Result<DeleteResult.Error>> errorResults = Collections.singletonList(result);
    when(client.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(errorResults);

    S3Utils.deleteS3ObjectsQuietly(client, "test-bucket", Arrays.asList("key1", "key2"));
    verify(client, times(2)).removeObjects(any(RemoveObjectsArgs.class));
    verify(client, times(2)).removeObject(any(RemoveObjectArgs.class));

    // 3. Batch failure throwing exception (falls back to individual deletion)
    doThrow(new RuntimeException("Batch delete unsupported"))
        .when(client)
        .removeObjects(any(RemoveObjectsArgs.class));
    S3Utils.deleteS3ObjectsQuietly(client, "test-bucket", Arrays.asList("key3", "key4"));
    verify(client, times(3)).removeObjects(any(RemoveObjectsArgs.class));
    verify(client, times(4)).removeObject(any(RemoveObjectArgs.class));
  }

  @Test
  public void testExtractObjectName() throws Exception {
    assertNull(S3Utils.extractObjectName(null));

    // Result containing null item
    @SuppressWarnings("unchecked")
    Result<Item> nullResult = mock(Result.class);
    when(nullResult.get()).thenReturn(null);
    assertNull(S3Utils.extractObjectName(nullResult));

    // Result containing valid item
    Item item = mock(Item.class);
    when(item.objectName()).thenReturn("part.00001");
    @SuppressWarnings("unchecked")
    Result<Item> validResult = mock(Result.class);
    when(validResult.get()).thenReturn(item);
    assertEquals("part.00001", S3Utils.extractObjectName(validResult));

    // Result throwing exception
    @SuppressWarnings("unchecked")
    Result<Item> errorResult = mock(Result.class);
    when(errorResult.get()).thenThrow(new RuntimeException("Failed reading item"));
    try {
      S3Utils.extractObjectName(errorResult);
      org.junit.Assert.fail("Expected RuntimeException to be thrown");
    } catch (RuntimeException e) {
      assertEquals("Failed to read S3 item result", e.getMessage());
    }
  }

  @Test
  public void testGetObjectSize() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null checks
    assertEquals(-1L, S3Utils.getObjectSize(null, "bucket", "key"));
    assertEquals(-1L, S3Utils.getObjectSize(client, null, "key"));
    assertEquals(-1L, S3Utils.getObjectSize(client, "bucket", null));

    // stat returns null
    when(client.statObject(any(StatObjectArgs.class))).thenReturn(null);
    assertEquals(-1L, S3Utils.getObjectSize(client, "bucket", "key"));

    // stat returns response with size
    StatObjectResponse statResponse = mock(StatObjectResponse.class);
    when(statResponse.size()).thenReturn(1024L);
    when(client.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);
    assertEquals(1024L, S3Utils.getObjectSize(client, "bucket", "key"));

    // stat throws exception
    when(client.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("NoSuchKey"));
    assertEquals(-1L, S3Utils.getObjectSize(client, "bucket", "key"));
  }

  @Test
  public void testObjectExists() throws Exception {
    MinioClient client = mock(MinioClient.class);

    // Null checks
    assertFalse(S3Utils.objectExists(null, "bucket", "key"));
    assertFalse(S3Utils.objectExists(client, null, "key"));
    assertFalse(S3Utils.objectExists(client, "bucket", null));

    // Object exists (statObject returns response)
    StatObjectResponse statResponse = mock(StatObjectResponse.class);
    when(client.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);
    assertTrue(S3Utils.objectExists(client, "bucket", "key"));

    // Object does not exist (statObject throws exception)
    when(client.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("NoSuchKey"));
    assertFalse(S3Utils.objectExists(client, "bucket", "key"));
  }

  @Test
  public void testParseErrorResponseNull() {
    assertEquals(S3ErrorType.UNKNOWN, S3Utils.parseErrorResponse(null));
  }

  @Test
  public void testParseErrorResponseCodes() throws Exception {
    assertEquals(
        S3ErrorType.NO_SUCH_KEY, S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchKey")));
    assertEquals(
        S3ErrorType.NO_SUCH_KEY,
        S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchBucket")));
    assertEquals(
        S3ErrorType.NO_SUCH_KEY,
        S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchUpload")));
    assertEquals(
        S3ErrorType.PRECONDITION_FAILED,
        S3Utils.parseErrorResponse(createExceptionWithCode("PreconditionFailed")));
    assertEquals(
        S3ErrorType.CONFLICT,
        S3Utils.parseErrorResponse(createExceptionWithCode("ObjectAlreadyExists")));
    assertEquals(
        S3ErrorType.ACCESS_DENIED,
        S3Utils.parseErrorResponse(createExceptionWithCode("AccessDenied")));
    assertEquals(
        S3ErrorType.API_NOT_IMPLEMENTED,
        S3Utils.parseErrorResponse(createExceptionWithCode("APINotImplemented")));
    assertEquals(
        S3ErrorType.API_NOT_IMPLEMENTED,
        S3Utils.parseErrorResponse(createExceptionWithCode("NotImplemented")));
    assertEquals(
        S3ErrorType.UNKNOWN, S3Utils.parseErrorResponse(createExceptionWithCode("InternalError")));
  }

  @Test
  public void testParseErrorResponseHttp501() {
    Response httpResponse =
        new Response.Builder()
            .request(new Request.Builder().url("https://example.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(501)
            .message("Not Implemented")
            .build();
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn("");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, httpResponse, null);
    assertEquals(S3ErrorType.API_NOT_IMPLEMENTED, S3Utils.parseErrorResponse(ex));
  }

  private ErrorResponseException createExceptionWithCode(String code) {
    ErrorResponse errorResponse = mock(ErrorResponse.class);
    when(errorResponse.code()).thenReturn(code);
    return new ErrorResponseException(errorResponse, null, null);
  }
}
