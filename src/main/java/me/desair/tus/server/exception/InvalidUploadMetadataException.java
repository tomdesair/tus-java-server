package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the Upload-Metadata header is invalid or malformed. */
public class InvalidUploadMetadataException extends TusException {

  public InvalidUploadMetadataException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
