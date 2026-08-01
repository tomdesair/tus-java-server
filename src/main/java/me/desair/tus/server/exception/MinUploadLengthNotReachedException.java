package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when requested upload length is smaller than the minimum allowed size. */
public class MinUploadLengthNotReachedException extends TusException {

  public MinUploadLengthNotReachedException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
