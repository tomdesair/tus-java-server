package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the request payload size exceeds the maximum allowed append size. */
public class MaxAppendSizeExceededException extends TusException {

  public MaxAppendSizeExceededException(String message) {
    super(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, message);
  }
}
