package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility helper methods for interacting with Azure Blob Storage and evaluating error responses.
 */
public final class AzureUtils {

  private static final Logger log = LoggerFactory.getLogger(AzureUtils.class);

  private AzureUtils() {
    // Utility class
  }

  /**
   * Checks whether a blob exists in Azure Blob Storage.
   *
   * @param blobClient The {@link BlobClient} to inspect
   * @return {@code true} if the blob exists; {@code false} if it does not exist, argument is null,
   *     or an error occurs
   */
  public static boolean blobExists(BlobClient blobClient) {
    if (blobClient == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(blobClient.exists());
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks whether a block blob exists in Azure Blob Storage.
   *
   * @param blobClient The {@link BlockBlobClient} to inspect
   * @return {@code true} if the blob exists; {@code false} if it does not exist, argument is null,
   *     or an error occurs
   */
  public static boolean blobExists(BlockBlobClient blobClient) {
    if (blobClient == null) {
      return false;
    }
    try {
      return Boolean.TRUE.equals(blobClient.exists());
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Checks whether a blob with the given name exists in the specified container.
   *
   * @param containerClient The {@link BlobContainerClient}
   * @param blobName The blob name to check
   * @return {@code true} if the blob exists; {@code false} if it does not exist, any argument is
   *     null, or an error occurs
   */
  public static boolean blobExists(BlobContainerClient containerClient, String blobName) {
    if (containerClient == null || blobName == null) {
      return false;
    }
    return blobExists(containerClient.getBlobClient(blobName));
  }

  /**
   * Quietly deletes a blob if it exists without throwing exceptions, logging errors at debug level.
   *
   * @param blobClient The {@link BlobClient} to remove
   */
  public static void deleteBlobQuietly(BlobClient blobClient) {
    if (blobClient == null) {
      return;
    }
    try {
      blobClient.deleteIfExists();
    } catch (Exception e) {
      log.debug("Failed to delete Azure blob quietly: {}", e.getMessage());
    }
  }

  /**
   * Quietly deletes a block blob if it exists without throwing exceptions.
   *
   * @param blobClient The {@link BlockBlobClient} to remove
   */
  public static void deleteBlobQuietly(BlockBlobClient blobClient) {
    if (blobClient == null) {
      return;
    }
    try {
      blobClient.deleteIfExists();
    } catch (Exception e) {
      log.debug("Failed to delete Azure block blob quietly: {}", e.getMessage());
    }
  }

  /**
   * Quietly deletes a blob with the given name from the container without throwing exceptions.
   *
   * @param containerClient The {@link BlobContainerClient}
   * @param blobName The blob name to remove
   */
  public static void deleteBlobQuietly(BlobContainerClient containerClient, String blobName) {
    if (containerClient == null || blobName == null) {
      return;
    }
    deleteBlobQuietly(containerClient.getBlobClient(blobName));
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

    if (statusCode == 401
        || statusCode == 403
        || errorCodeStr.contains("authorizationfailure")
        || errorCodeStr.contains("cannotverifycopysource")
        || errorCodeStr.contains("noauthenticationinformation")) {
      return AzureErrorType.ACCESS_DENIED;
    }

    return AzureErrorType.UNKNOWN;
  }
}
