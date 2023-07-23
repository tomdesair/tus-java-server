package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when receiving a request with a tus protocol version we do not support <br>
 * The Tus-Resumable header MUST be included in every request and response except for OPTIONS
 * requests. The value MUST be the version of the protocol used by the Client or the Server. If the
 * the version specified by the Client is not supported by the Server, it MUST respond with the 412
 * Precondition Failed status and MUST include the Tus-Version header into the response. In
 * addition, the Server MUST NOT process the request. <br>
 * (https://tus.io/protocols/resumable-upload.html#tus-resumable)
 */
public class InvalidTusResumableException extends TusException {

  public InvalidTusResumableException(String message) {
    super(HttpServletResponse.SC_PRECONDITION_FAILED, message);
  }
}
