package me.desair.tus.server.upload;

import java.io.InputStream;
import java.util.UUID;

/**
 * Interface to a service that is able to store the (partially) uploaded files.
 */
public interface UploadStorageService {

    /**
     * Method to retrieve the upload info by ID
     * @param id The id for which to lookup the upload info. This parameter can be null.
     * @return The upload info matching the given ID, or null when not found.
     */
    UploadInfo getUploadInfo(final UUID id);

    /**
     * Max upload size in bytes allowed or configured in this Storage Service
     * @return the maximum number of bytes, or zero if there is no maximum
     */
    long getMaxSizeInBytes();

    /**
     * Append the bytes in the give {@link InputStream} to the upload with the given ID starting at the provided offset.
     * This method also updates the {@link UploadInfo} corresponding to this upload.
     * @param id The ID of the upload
     * @param offset The offset at which to start appending
     * @param inputStream The input stream containing the bytes to append
     * @return The new {@link UploadInfo} for this upload
     */
    UploadInfo append(final UUID id, final Long offset, final InputStream inputStream);

    /**
     * Create an upload location with the given upload information
     * @param info The Upload information which has a unique ID
     * @return false if the given ID was already in use and a new attempt should be made, true otherwise
     */
    boolean create(final UploadInfo info);
}
