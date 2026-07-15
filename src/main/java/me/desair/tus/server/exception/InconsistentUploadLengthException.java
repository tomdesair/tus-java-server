package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the provided upload length is inconsistent. */
public class InconsistentUploadLengthException extends TusException {
  /**
   * Constructor.
   *
   * @param message Human-readable error message
   */
  public InconsistentUploadLengthException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
