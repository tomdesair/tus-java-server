package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.models.BlobStorageException;

/** Utility helper methods for parsing and evaluating Azure Blob Storage error responses. */
public final class AzureUtils {

  private AzureUtils() {
    // Utility class
  }

  /**
   * Parses a {@link BlobStorageException} into a strongly-typed {@link AzureErrorType}.
   *
   * @param exception The Azure BlobStorageException to evaluate
   * @return The corresponding AzureErrorType enum
   */
  public static AzureErrorType parseErrorResponse(BlobStorageException exception) {
    if (exception == null) {
      return AzureErrorType.UNKNOWN;
    }

    int statusCode = exception.getStatusCode();
    String rawErrorCode = "";
    try {
      if (exception.getErrorCode() != null) {
        rawErrorCode = exception.getErrorCode().toString();
      }
    } catch (Exception ignored) {
      // Defensive fallback if exception lacks initialized HTTP headers
    }

    String errorCodeStr = rawErrorCode.replaceAll("[^a-zA-Z]", "").toLowerCase();

    if (statusCode == 404
        || errorCodeStr.contains("blobnotfound")
        || errorCodeStr.contains("containernotfound")) {
      return AzureErrorType.BLOB_NOT_FOUND;
    }

    if (statusCode == 409) {
      if (errorCodeStr.contains("leasealreadypresent")
          || errorCodeStr.contains("leaseidmismatchwithleaseoperation")) {
        return AzureErrorType.LEASE_ALREADY_PRESENT;
      }
      if (errorCodeStr.contains("leasenotpresentwithleaseoperation")
          || errorCodeStr.contains("leaseidmissing")) {
        return AzureErrorType.LEASE_NOT_PRESENT;
      }
      return AzureErrorType.CONFLICT;
    }

    if (statusCode == 412 || errorCodeStr.contains("conditionnotmet")) {
      return AzureErrorType.PRECONDITION_FAILED;
    }

    if (statusCode == 501 || errorCodeStr.contains("apinotimplemented")) {
      return AzureErrorType.API_NOT_IMPLEMENTED;
    }

    if (statusCode == 403 || errorCodeStr.contains("authorizationfailure")) {
      return AzureErrorType.ACCESS_DENIED;
    }

    return AzureErrorType.UNKNOWN;
  }
}
