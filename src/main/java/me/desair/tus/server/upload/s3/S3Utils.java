package me.desair.tus.server.upload.s3;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;

/** Utility helper methods for parsing and evaluating MinIO S3 error responses. */
public final class S3Utils {

  private S3Utils() {
    // Utility class
  }

  /**
   * Parses an {@link ErrorResponseException} into a clean, strongly-typed {@link S3ErrorType}.
   *
   * @param exception The MinIO ErrorResponseException to evaluate
   * @return The corresponding S3ErrorType enum
   */
  public static S3ErrorType parseErrorResponse(ErrorResponseException exception) {
    if (exception == null) {
      return S3ErrorType.UNKNOWN;
    }

    ErrorResponse response = exception.errorResponse();
    String code = response != null ? response.code() : "";

    if ("NoSuchKey".equalsIgnoreCase(code) || "NoSuchBucket".equalsIgnoreCase(code)) {
      return S3ErrorType.NO_SUCH_KEY;
    }

    if ("PreconditionFailed".equalsIgnoreCase(code)) {
      return S3ErrorType.PRECONDITION_FAILED;
    }

    if ("ObjectAlreadyExists".equalsIgnoreCase(code)
        || "BucketAlreadyExists".equalsIgnoreCase(code)) {
      return S3ErrorType.CONFLICT;
    }

    if ("AccessDenied".equalsIgnoreCase(code)) {
      return S3ErrorType.ACCESS_DENIED;
    }

    return S3ErrorType.UNKNOWN;
  }
}
