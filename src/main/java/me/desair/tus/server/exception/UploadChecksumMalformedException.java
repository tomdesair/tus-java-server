package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the Upload-Checksum header is malformed. */
public class UploadChecksumMalformedException extends TusException {

  public UploadChecksumMalformedException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
