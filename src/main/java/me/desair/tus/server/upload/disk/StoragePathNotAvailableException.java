package me.desair.tus.server.upload.disk;

/** Exception thrown when the disk storage path cannot be read or created. */
public class StoragePathNotAvailableException extends RuntimeException {
  public StoragePathNotAvailableException(String message, Throwable e) {
    super(message, e);
  }
}
