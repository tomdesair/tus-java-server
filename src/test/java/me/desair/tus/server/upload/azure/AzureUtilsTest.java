package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.junit.Test;
import reactor.core.publisher.Mono;

public class AzureUtilsTest {

  private BlobStorageException createException(int statusCode, BlobErrorCode errorCode) {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getStatusCode()).thenReturn(statusCode);
    HttpHeaders headers = new HttpHeaders();
    if (errorCode != null) {
      headers.set("x-ms-error-code", errorCode.toString());
    }
    when(response.getHeaders()).thenReturn(headers);
    return new BlobStorageException("Test exception", response, errorCode);
  }

  @Test
  public void testSanitizePrefix() {
    assertEquals("", AzureUtils.sanitizePrefix(null));
    assertEquals("", AzureUtils.sanitizePrefix(""));
    assertEquals("", AzureUtils.sanitizePrefix("   "));
    assertEquals("", AzureUtils.sanitizePrefix("///"));
    assertEquals("locks/", AzureUtils.sanitizePrefix("locks"));
    assertEquals("locks/", AzureUtils.sanitizePrefix("/locks"));
    assertEquals("locks/", AzureUtils.sanitizePrefix("locks/"));
    assertEquals("locks/", AzureUtils.sanitizePrefix("/locks/"));
    assertEquals("a/b/c/", AzureUtils.sanitizePrefix("/a/b/c"));
    assertEquals("custom/", AzureUtils.sanitizePrefix("/custom///"));
  }

  @Test
  public void testBlobExists() {
    // Null checks
    assertFalse(AzureUtils.blobExists((BlobClient) null));
    assertFalse(AzureUtils.blobExists((BlobContainerClient) null, "test-blob"));

    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any())).thenReturn(Mono.error(new RuntimeException("Connection error")));
    BlobContainerClient containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .httpClient(httpClient)
            .buildClient();

    assertFalse(AzureUtils.blobExists(containerClient, null));

    // Blob exists == true
    BlobClient blobClient = mock(BlobClient.class);
    when(blobClient.exists()).thenReturn(true);
    assertTrue(AzureUtils.blobExists(blobClient));

    // Blob exists == false
    when(blobClient.exists()).thenReturn(false);
    assertFalse(AzureUtils.blobExists(blobClient));

    // Blob exists throws exception
    when(blobClient.exists()).thenThrow(new RuntimeException("Connection error"));
    assertFalse(AzureUtils.blobExists(blobClient));

    // BlockBlobClient overloads
    assertFalse(AzureUtils.blobExists((BlockBlobClient) null));
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient("test-blob").getBlockBlobClient();
    assertFalse(AzureUtils.blobExists(blockBlobClient));

    // Delegating method with mock HTTP client throwing exception
    assertFalse(AzureUtils.blobExists(containerClient, "test-blob"));
  }

  @Test
  public void testDeleteBlobQuietly() {
    // Null checks (should not throw)
    AzureUtils.deleteBlobQuietly((BlobClient) null);
    AzureUtils.deleteBlobQuietly((BlobContainerClient) null, "test-blob");

    HttpClient httpClient = mock(HttpClient.class);
    when(httpClient.send(any())).thenReturn(Mono.error(new RuntimeException("Connection error")));
    BlobContainerClient containerClient =
        new BlobContainerClientBuilder()
            .endpoint("https://dummyaccount.blob.core.windows.net")
            .containerName("dummy-container")
            .httpClient(httpClient)
            .buildClient();

    AzureUtils.deleteBlobQuietly(containerClient, null);

    // Normal deletion on BlobClient mock
    BlobClient blobClient = mock(BlobClient.class);
    AzureUtils.deleteBlobQuietly(blobClient);
    verify(blobClient, times(1)).deleteIfExists();

    // Exception handled quietly on BlobClient mock
    doThrow(new RuntimeException("Azure delete failed")).when(blobClient).deleteIfExists();
    AzureUtils.deleteBlobQuietly(blobClient);
    verify(blobClient, times(2)).deleteIfExists();

    // BlockBlobClient overloads
    AzureUtils.deleteBlobQuietly((BlockBlobClient) null);
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient("test-blob").getBlockBlobClient();
    AzureUtils.deleteBlobQuietly(blockBlobClient);

    // Delegating method on container client with mock HTTP client throwing exception
    AzureUtils.deleteBlobQuietly(containerClient, "test-blob");
  }

  @Test
  public void testParseErrorResponseNull() {
    assertEquals(AzureErrorType.UNKNOWN, AzureUtils.parseErrorResponse(null));
  }

  @Test
  public void testParseErrorResponseBlobNotFound() {
    BlobStorageException ex = createException(404, BlobErrorCode.BLOB_NOT_FOUND);
    assertEquals(AzureErrorType.BLOB_NOT_FOUND, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseLeaseAlreadyPresent() {
    BlobStorageException ex = createException(409, BlobErrorCode.LEASE_ALREADY_PRESENT);
    assertEquals(AzureErrorType.LEASE_ALREADY_PRESENT, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseLeaseNotPresent() {
    BlobStorageException ex =
        createException(409, BlobErrorCode.LEASE_NOT_PRESENT_WITH_LEASE_OPERATION);
    assertEquals(AzureErrorType.LEASE_NOT_PRESENT, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseConflict() {
    BlobStorageException ex = createException(409, BlobErrorCode.BLOB_ALREADY_EXISTS);
    assertEquals(AzureErrorType.CONFLICT, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponsePreconditionFailed() {
    BlobStorageException ex = createException(412, BlobErrorCode.CONDITION_NOT_MET);
    assertEquals(AzureErrorType.PRECONDITION_FAILED, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseApiNotImplemented() {
    BlobStorageException ex = createException(501, null);
    assertEquals(AzureErrorType.API_NOT_IMPLEMENTED, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseAccessDenied() {
    BlobStorageException ex = createException(403, BlobErrorCode.AUTHORIZATION_FAILURE);
    assertEquals(AzureErrorType.ACCESS_DENIED, AzureUtils.parseErrorResponse(ex));

    BlobStorageException ex401 = createException(401, null);
    assertEquals(AzureErrorType.ACCESS_DENIED, AzureUtils.parseErrorResponse(ex401));
  }

  @Test
  public void testParseErrorResponseUnknown() {
    BlobStorageException ex = createException(500, null);
    assertEquals(AzureErrorType.UNKNOWN, AzureUtils.parseErrorResponse(ex));
  }

  @Test
  public void testParseErrorResponseErrorCodeStringMatching() {
    BlobStorageException exContainer = createException(404, BlobErrorCode.CONTAINER_NOT_FOUND);
    assertEquals(AzureErrorType.BLOB_NOT_FOUND, AzureUtils.parseErrorResponse(exContainer));

    BlobStorageException exLeaseMismatch =
        createException(409, BlobErrorCode.LEASE_ID_MISMATCH_WITH_LEASE_OPERATION);
    assertEquals(
        AzureErrorType.LEASE_ALREADY_PRESENT, AzureUtils.parseErrorResponse(exLeaseMismatch));

    BlobStorageException exLeaseMissing = createException(409, BlobErrorCode.LEASE_ID_MISSING);
    assertEquals(AzureErrorType.LEASE_NOT_PRESENT, AzureUtils.parseErrorResponse(exLeaseMissing));
  }

  @Test
  public void testParseErrorResponseErrorCodeThrowsExceptionHandled() {
    HttpResponse response = mock(HttpResponse.class);
    when(response.getHeaders()).thenThrow(new RuntimeException("Simulated error code failure"));
    BlobStorageException ex = new BlobStorageException("Test exception", response, null);
    assertEquals(AzureErrorType.UNKNOWN, AzureUtils.parseErrorResponse(ex));
  }
}
