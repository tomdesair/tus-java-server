package me.desair.tus.server.exception;

public class UploadAlreadyLockedException extends TusException {
  public UploadAlreadyLockedException(String message) {
    // 423 is LOCKED (WebDAV rfc 4918)
    super(423, message);
  }
}
