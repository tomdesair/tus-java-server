package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.Test;

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
  }

  @Test
  public void testParseErrorResponseUnknown() {
    BlobStorageException ex = createException(500, null);
    assertEquals(AzureErrorType.UNKNOWN, AzureUtils.parseErrorResponse(ex));
  }
}
