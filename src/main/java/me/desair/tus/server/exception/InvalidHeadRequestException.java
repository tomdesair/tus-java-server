package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when a HEAD status request contains forbidden headers. */
public class InvalidHeadRequestException extends TusException {

  public InvalidHeadRequestException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
