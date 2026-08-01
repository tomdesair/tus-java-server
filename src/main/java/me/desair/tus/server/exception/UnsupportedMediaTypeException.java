package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/** Exception thrown when the Content-Type header specifies an unsupported media type. */
public class UnsupportedMediaTypeException extends TusException {

  public UnsupportedMediaTypeException(String message) {
    super(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, message);
  }
}
