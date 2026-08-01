package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when request payload size is below the minimum allowed append size. */
public class MinAppendSizeNotMetException extends TusException {

  public MinAppendSizeNotMetException(String message) {
    super(HttpServletResponse.SC_BAD_REQUEST, message);
  }
}
