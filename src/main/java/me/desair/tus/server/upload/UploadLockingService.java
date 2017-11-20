package me.desair.tus.server.upload;

import me.desair.tus.server.exception.TusException;

import java.io.IOException;

/**
 * Service interface that can lock a specific upload so that it cannot be modified by other requests/threads.
 */
public interface UploadLockingService {

    /**
     * If the given URI represents a valid upload, lock that upload for processing
     * @param requestURI The URI that potentially represents an upload
     * @return The lock on the upload, or null if not lock was applied
     * @throws TusException If the upload is already locked
     */
    UploadLock lockUploadByUri(String requestURI) throws TusException;

    /**
     * Clean up any stale locks that are still present
     * @throws TusException When cleaning a stale lock fails
     */
    void cleanupStaleLocks() throws IOException;
}
