package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the Upload-Complete header is missing or invalid. */
public class InvalidUploadCompleteHeaderException extends TusException {

  public InvalidUploadCompleteHeaderException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
