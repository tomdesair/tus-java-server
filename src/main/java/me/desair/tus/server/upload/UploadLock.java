package me.desair.tus.server.upload;

/**
 * Interface that represents a lock on an upload
 */
public interface UploadLock {

    /**
     * Get the upload URI of the upload that is locked by this lock
     * @return The URI of the locked upload
     */
    String getUploadUri();

    /**
     * Method to release the lock on an upload when done processing it
     */
    void release();

}
