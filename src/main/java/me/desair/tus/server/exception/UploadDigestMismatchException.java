package me.desair.tus.server.exception;

/**
 * Exception thrown when the client provided digest does not match the digest calculated by the
 * server, in compliance with RFC 9530.
 */
public class UploadDigestMismatchException extends TusException {
  public UploadDigestMismatchException(String message) {
    super(400, message);
  }
}
