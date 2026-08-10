package me.desair.tus.server.upload.s3;

/** Standardized S3/MinIO error types parsed from {@link io.minio.errors.ErrorResponseException}. */
public enum S3ErrorType {
  /** Target S3 object or key does not exist in bucket (HTTP 404 / NoSuchKey). */
  NO_SUCH_KEY,

  /**
   * Conditional request precondition failed (HTTP 412 / PreconditionFailed / If-None-Match match).
   */
  PRECONDITION_FAILED,

  /** Lock or resource already exists / conflict (HTTP 409 / ObjectAlreadyExists). */
  CONFLICT,

  /** Permission denied or invalid credentials (HTTP 403 / AccessDenied). */
  ACCESS_DENIED,

  /** Unknown or unmapped S3 error response. */
  UNKNOWN
}
