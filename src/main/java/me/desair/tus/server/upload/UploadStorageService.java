package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.ServletOutputStream;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;

/**
 * Interface to a service that is able to store the (partially) uploaded files.
 */
public interface UploadStorageService {

    /**
     * Method to retrieve the upload info by its upload URL
     * @param uploadUrl The URL corresponding to this upload. This parameter can be null.
     * @param ownerKey
     * @return The upload info matching the given URL, or null when not found.
     */
    UploadInfo getUploadInfo(final String uploadUrl, final String ownerKey) throws IOException;

    /**
     * The URI which is configured as the upload endpoint
     * @return The URI
     */
    String getUploadURI();

    /**
     * Append the bytes in the give {@link InputStream} to the upload with the given ID starting at the provided offset.
     * This method also updates the {@link UploadInfo} corresponding to this upload. The Upload Storage server should
     * not exceed its max upload size when writing bytes.
     * @param upload The ID of the upload
     * @param inputStream The input stream containing the bytes to append
     * @return The new {@link UploadInfo} for this upload
     */
    UploadInfo append(final UploadInfo upload, final InputStream inputStream) throws IOException, TusException;

    /**
     * Limit the maximum upload size to the given value
     * @param maxUploadSize The maximum upload limit to set
     */
    void setMaxUploadSize(Long maxUploadSize);

    /**
     * Get the maximum upload size configured on this storage service
     * @return The maximum upload size or zero if no maximum
     */
    long getMaxUploadSize();

    /**
     * Create an upload location with the given upload information
     * @param info The Upload information to use to create the new upload
     * @param ownerKey
     * @return An {@link UploadInfo} object containing the information used to create the upload and the unique ID of this upload
     */
    UploadInfo create(final UploadInfo info, final String ownerKey) throws IOException;

    /**
     * Update the upload information for the provided ID.
     * @param uploadInfo The upload info object containing the ID and information to update
     */
    void update(final UploadInfo uploadInfo) throws IOException, UploadNotFoundException;

    /**
     * Get the uploaded bytes corresponding to the given upload URL as a stream
     * @param uploadURI The URI
     * @param ownerKey
     * @return an {@link OutputStream} containing the bytes of the upload
     */
    InputStream getUploadedBytes(final String uploadURI, final String ownerKey) throws IOException, UploadNotFoundException;

    /**
     * Copy the uploaded bytes to the given output stream
     * @param info The upload of which we should copy the bytes
     * @param outputStream The output stream where we have to copy the bytes to
     */
    void copyUploadTo(UploadInfo info, OutputStream outputStream) throws UploadNotFoundException, IOException;

    /**
     * Clean up any upload data that is expired according to the configured expiration time
     * @param uploadLockingService
     */
    void cleanupExpiredUploads(final UploadLockingService uploadLockingService);

    /**
     * Remove the given last amount of bytes from the uploaded data
     * @param uploadInfo Upload of which to remove the bytes
     * @param byteCount The number of bytes to remove at the end
     */
    void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount) throws UploadNotFoundException, IOException;

    /**
     * Terminate completed and unfinished uploads allowing the Server to free up used resources.
     * @param uploadInfo The upload to terminate
     */
    void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException;

}
