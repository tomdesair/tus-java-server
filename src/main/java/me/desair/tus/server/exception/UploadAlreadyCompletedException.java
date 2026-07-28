package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when trying to append to or modify an upload that is already completed. */
public class UploadAlreadyCompletedException extends TusException {
  /**
   * Constructor.
   *
   * @param message Human-readable error message
   */
  public UploadAlreadyCompletedException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
