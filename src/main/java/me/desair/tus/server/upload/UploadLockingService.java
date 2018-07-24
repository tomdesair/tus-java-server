package me.desair.tus.server.upload;

import java.io.IOException;
import java.util.UUID;

import me.desair.tus.server.exception.TusException;

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
    UploadLock lockUploadByUri(String requestURI) throws TusException, IOException;

    /**
     * Clean up any stale locks that are still present
     * @throws TusException When cleaning a stale lock fails
     */
    void cleanupStaleLocks() throws IOException;

    /**
     * Check if the upload with the given ID is currently locked
     * @param id The ID of the upload to check
     * @return True if the upload is locked, false otherwise
     */
    boolean isLocked(UUID id);

    /**
     * Set an instance if IdFactory to be used for creating identities and extracting them from uploadURIs
     *
     * @param idFactory
     */
    void setIdFactory(UploadIdFactory idFactory);
}
