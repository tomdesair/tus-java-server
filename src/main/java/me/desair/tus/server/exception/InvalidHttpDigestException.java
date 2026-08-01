package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when HTTP Digest headers (Content-Digest, Repr-Digest, Want-Repr-Digest) are
 * invalid.
 */
public class InvalidHttpDigestException extends TusException {

  public InvalidHttpDigestException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
