package me.desair.tus.server.exception;

/**
 * Exception thrown when accessing an upload that is still in progress and this is not supported by
 * the operation.
 */
public class UploadInProgressException extends TusException {
  /** Constructor. */
  public UploadInProgressException(String message) {
    // 422 Unprocessable Entity
    // The request was well-formed but was unable to be followed due to semantic errors.
    super(422, message);
  }
}
