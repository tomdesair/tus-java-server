package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when appended content pushes total offset past declared upload length. */
public class UploadLengthExceededException extends TusException {

  public UploadLengthExceededException(String message) {
    super(HttpServletResponse.SC_CONFLICT, message);
  }
}
