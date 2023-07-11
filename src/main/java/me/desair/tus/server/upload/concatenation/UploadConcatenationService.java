package me.desair.tus.server.upload.concatenation;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;

/**
 * Interface for a service that is able to concatenate partial uploads into a concatenated upload
 */
public interface UploadConcatenationService {

  /**
   * Merge the given concatenated upload if all the underlying partial uploads are completed. If the
   * underlying partial uploads are still in-progress, this method does nothing. Otherwise the
   * upload information of the concatenated upload is updated.
   *
   * @param uploadInfo The concatenated upload
   * @throws IOException If merging the upload fails
   * @throws UploadNotFoundException When one of the partial uploads cannot be found
   */
  void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException;

  /**
   * Get the concatenated bytes of this concatenated upload
   *
   * @param uploadInfo The concatenated upload
   * @return The concatenated bytes, or null if this upload is still in progress
   * @throws IOException When return the concatenated bytes fails
   * @throws UploadNotFoundException When the or one of the partial uploads cannot be found
   */
  InputStream getConcatenatedBytes(UploadInfo uploadInfo)
      throws IOException, UploadNotFoundException;

  /**
   * Get all underlying partial uploads associated with the given concatenated upload
   *
   * @param info The concatenated upload
   * @return The underlying partial uploads
   * @throws IOException When retrieving the underlying partial uploads fails
   * @throws UploadNotFoundException When one of the partial uploads cannot be found
   */
  List<UploadInfo> getPartialUploads(UploadInfo info) throws IOException, UploadNotFoundException;
}
