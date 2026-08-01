package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the Upload-Offset header is missing or malformed in a request. */
public class InvalidUploadOffsetHeaderException extends TusException {

  public InvalidUploadOffsetHeaderException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
