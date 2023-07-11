package me.desair.tus.server.upload;

import java.io.IOException;

/** Interface that represents a lock on an upload */
public interface UploadLock extends AutoCloseable {

  /**
   * Get the upload URI of the upload that is locked by this lock
   *
   * @return The URI of the locked upload
   */
  String getUploadUri();

  /**
   * Method to release the lock on an upload when done processing it. It's possible that this method
   * is called multiple times within the same request
   */
  void release();

  @Override
  void close() throws IOException;
}
