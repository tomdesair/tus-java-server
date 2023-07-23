package me.desair.tus.server.exception;

/**
 * Exception thrown when the client provided checksum does not match the checksum calculated by the
 * server
 */
public class UploadChecksumMismatchException extends TusException {
  public UploadChecksumMismatchException(String message) {
    super(460, message);
  }
}
