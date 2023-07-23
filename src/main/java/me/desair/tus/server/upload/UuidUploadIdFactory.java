package me.desair.tus.server.upload;

import java.io.Serializable;
import java.util.UUID;

/**
 * Factory to create unique upload IDs. This factory can also parse the upload identifier from a
 * given upload URL.
 */
public class UuidUploadIdFactory extends UploadIdFactory {

  @Override
  protected Serializable getIdValueIfValid(String extractedUrlId) {
    UUID id = null;
    try {
      id = UUID.fromString(extractedUrlId);
    } catch (IllegalArgumentException ex) {
      id = null;
    }

    return id;
  }

  @Override
  public synchronized UploadId createId() {
    return new UploadId(UUID.randomUUID());
  }
}
