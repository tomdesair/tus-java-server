package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Interface for a service that is able to concatenate partial uploads
 * into a concatenated final upload
 */
public interface UploadConcatenationService {

    /**
     * Merge the given concatenated upload if all the underlying partial uploads are completed.
     * If the underlying partial uploads are still in-progress, this method does nothing. Otherwise
     * the upload information of the concatenated upload is updated.
     *
     * @param uploadInfo The concatenated upload
     * @throws IOException If merging the upload fails
     */
    void merge(final UploadInfo uploadInfo) throws IOException;

    /**
     * Get the concatenated bytes of this concatenated upload
     * @param uploadInfo The concatenated upload
     * @return The concatenated bytes, or null if this upload is still in progress
     * @throws IOException When return the concatenated bytes fails
     */
    InputStream getConcatenatedBytes(UploadInfo uploadInfo) throws IOException;

    /**
     * Get all underlying partial uploads associated with the given concatenated upload
     * @param info The concatenated upload
     * @return The underlying partial uploads
     * @throws IOException When retrieving the underlying partial uploads fails
     */
    List<UploadInfo> getPartialUploads(UploadInfo info) throws IOException;
}
