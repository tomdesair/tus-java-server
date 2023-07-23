package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the given upload ID was not found <br>
 * If the resource is not found, the Server SHOULD return either the 404 Not Found, 410 Gone or 403
 * Forbidden status without the Upload-Offset header.
 */
public class UploadNotFoundException extends TusException {
  public UploadNotFoundException(String message) {
    super(HttpServletResponse.SC_NOT_FOUND, message);
  }
}
