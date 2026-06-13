package me.desair.tus.server.upload;

import java.io.IOException;
import me.desair.tus.server.exception.TusException;

/**
 * Service interface that can lock a specific upload so that it cannot be modified by other
 * requests/threads.
 */
public interface UploadLockingService {

  /**
   * If the given URI represents a valid upload, lock that upload for processing.
   *
   * @param requestUri The URI that potentially represents an upload
   * @return The lock on the upload, or null if not lock was applied
   * @throws TusException If the upload is already locked
   */
  UploadLock lockUploadByUri(String requestUri) throws TusException, IOException;

  /**
   * Clean up any stale locks that are still present.
   *
   * @throws IOException When cleaning a stale lock fails
   */
  void cleanupStaleLocks() throws IOException;

  /**
   * Check if the upload with the given ID is currently locked.
   *
   * @param id The ID of the upload to check
   * @return True if the upload is locked, false otherwise
   */
  boolean isLocked(UploadId id);

  /**
   * Set an instance if IdFactory to be used for creating identities and extracting them from
   * uploadUris.
   *
   * @param idFactory The {@link UploadIdFactory} to use within this locking service
   */
  void setIdFactory(UploadIdFactory idFactory);

  /**
   * Register the input stream associated with the active request URI so that it can be interrupted
   * if lock contention occurs.
   *
   * @param requestUri The request URI of the active request
   * @param inputStream The input stream of the active request
   */
  default void registerInputStream(String requestUri, java.io.InputStream inputStream) {
    // No-op by default for backwards compatibility
  }

  /**
   * Request that the lock for the given request URI be released. This might involve interrupting
   * the active request's input stream.
   *
   * @param requestUri The request URI of the upload lock to release
   */
  default void requestLockRelease(String requestUri) {
    // No-op by default for backwards compatibility
  }
}
