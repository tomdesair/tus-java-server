package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when request URI contains unsafe path traversal components. */
public class UnsafePathException extends TusException {

  public UnsafePathException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
