package me.desair.tus.server.upload;

import java.io.InputStream;
import java.io.OutputStream;
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
     * Method to retrieve the upload info by its upload URL
     * @param uploadUrl The URL corresponding to this upload. This parameter can be null.
     * @return The upload info matching the given URL, or null when not found.
     */
    UploadInfo getUploadInfo(final String uploadUrl);

    /**
     * The URI which is configured as the upload endpoint
     * @return The URI
     */
    String getUploadURI();

    /**
     * Max upload size in bytes allowed or configured in this Storage Service
     * @return the maximum number of bytes, or zero if there is no maximum
     */
    long getMaxSizeInBytes();

    /**
     * Append the bytes in the give {@link InputStream} to the upload with the given ID starting at the provided offset.
     * This method also updates the {@link UploadInfo} corresponding to this upload. The Upload Storage server should
     * not exceed its max upload size when writing bytes.
     * @param id The ID of the upload
     * @param offset The offset at which to start appending
     * @param inputStream The input stream containing the bytes to append
     * @return The new {@link UploadInfo} for this upload
     */
    UploadInfo append(final UUID id, final Long offset, final InputStream inputStream);

    /**
     * Create an upload location with the given upload information
     * @param info The Upload information to use to create the new upload
     * @return An {@link UploadInfo} object containing the information used to create the upload and the unique ID of this upload
     */
    UploadInfo create(final UploadInfo info);

    /**
     * Update the upload information for the provided ID.
     * @param uploadInfo The upload info object containing the ID and information to update
     */
    void update(final UploadInfo uploadInfo);

    /**
     * Get the uploaded bytes corresponding to the given upload URL as a stream
     * @param uploadURI The URI
     * @return an {@link OutputStream} containing the bytes of the upload
     */
    OutputStream getUploadedBytes(final String uploadURI);
}
