package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
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
   * Lock the upload resource associated with the given {@link UploadId}.
   *
   * @param id The upload ID to lock
   * @return The lock on the upload, or null if no lock was applied
   * @throws TusException If the upload is already locked
   * @throws IOException If locking fails
   */
  default UploadLock lockUpload(UploadId id) throws TusException, IOException {
    if (id == null) {
      return null;
    }
    return lockUploadByUri(id.toString());
  }

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
   * if lock contention occurs or during service shutdown.
   *
   * @param requestUri The request URI of the active request
   * @param inputStream The input stream of the active request
   */
  default void registerInputStream(String requestUri, InputStream inputStream) {
    // No-op by default for backwards compatibility
  }

  /**
   * Unregisters the active input stream associated with the given request URI once streaming has
   * completed or failed, preventing stale stream references from lingering in memory or being
   * repeatedly polled by background watchdog daemon threads across long service lifecycles.
   *
   * @param requestUri The request URI of the active request
   */
  default void unregisterInputStream(String requestUri) {
    // No-op by default for backwards compatibility
  }

  /**
   * Request that the lock for the given request URI be released. This interrupts the active
   * request's input stream locally or signals other cluster replicas to interrupt their active
   * stream via a stop signal.
   *
   * @param requestUri The request URI of the upload lock to release
   */
  default void requestLockRelease(String requestUri) {
    // No-op by default for backwards compatibility
  }

  /**
   * Closes resources, interrupts any active in-flight request streams, and shuts down background
   * watchdog threads associated with this locking service.
   *
   * @throws IOException If closing fails
   */
  default void close() throws IOException {
    // No-op by default for backwards compatibility
  }

  /**
   * Cleans up lock artifacts and stop signals associated with the given upload.
   *
   * <p>Called upon upload termination (e.g. DELETE) or expiration cleanup so that locking
   * implementations can delete backend-specific lock files, blobs, or contention signals without
   * storage services needing awareness of locking internals.
   *
   * @param id The ID of the upload to clean up
   * @throws IOException If cleanup fails
   */
  default void cleanupLock(UploadId id) throws IOException {
    // No-op by default for backwards compatibility
  }
}
