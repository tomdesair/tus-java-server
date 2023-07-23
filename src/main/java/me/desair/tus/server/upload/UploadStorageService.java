package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;

/** Interface to a service that is able to store the (partially) uploaded files. */
public interface UploadStorageService {

  /**
   * Method to retrieve the upload info by its upload URL.
   *
   * @param uploadUrl The URL corresponding to this upload. This parameter can be null.
   * @param ownerKey A key representing the owner of the upload
   * @return The upload info matching the given URL, or null when not found.
   */
  UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException;

  /**
   * Method to retrieve the upload info by its ID.
   *
   * @param id The ID of the upload
   * @return The matching upload info
   * @throws IOException When the service is not able to retrieve the upload information
   */
  UploadInfo getUploadInfo(UploadId id) throws IOException;

  /**
   * The URI which is configured as the upload endpoint.
   *
   * @return The URI
   */
  String getUploadUri();

  /**
   * Append the bytes in the give {@link InputStream} to the upload with the given ID starting at
   * the provided offset. This method also updates the {@link UploadInfo} corresponding to this
   * upload. The Upload Storage server should not exceed its max upload size when writing bytes.
   *
   * @param upload The ID of the upload
   * @param inputStream The input stream containing the bytes to append
   * @return The new {@link UploadInfo} for this upload
   */
  UploadInfo append(UploadInfo upload, InputStream inputStream) throws IOException, TusException;

  /**
   * Limit the maximum upload size to the given value.
   *
   * @param maxUploadSize The maximum upload limit to set
   */
  void setMaxUploadSize(Long maxUploadSize);

  /**
   * Get the maximum upload size configured on this storage service.
   *
   * @return The maximum upload size or zero if no maximum
   */
  long getMaxUploadSize();

  /**
   * Create an upload location with the given upload information.
   *
   * @param info The Upload information to use to create the new upload
   * @param ownerKey A key representing the owner of the upload
   * @return An {@link UploadInfo} object containing the information used to create the upload and
   *     its unique ID
   */
  UploadInfo create(UploadInfo info, String ownerKey) throws IOException;

  /**
   * Update the upload information for the provided ID.
   *
   * @param uploadInfo The upload info object containing the ID and information to update
   */
  void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException;

  /**
   * Get the uploaded bytes corresponding to the given upload URL as a stream.
   *
   * @param uploadUri The URI
   * @param ownerKey The owner key of this upload
   * @return an {@link OutputStream} containing the bytes of the upload
   */
  InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, UploadNotFoundException;

  /**
   * Get the uploaded bytes corresponding to the given upload ID as a stream.
   *
   * @param id the ID of the upload
   * @return an {@link OutputStream} containing the bytes of the upload
   * @throws IOException When retrieving the bytes from the storage layer fails
   * @throws UploadNotFoundException When the proved id is not linked to an upload
   */
  InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException;

  /**
   * Copy the uploaded bytes to the given output stream.
   *
   * @param info The upload of which we should copy the bytes
   * @param outputStream The output stream where we have to copy the bytes to
   */
  void copyUploadTo(UploadInfo info, OutputStream outputStream)
      throws UploadNotFoundException, IOException;

  /**
   * Clean up any upload data that is expired according to the configured expiration time.
   *
   * @param uploadLockingService An {@link UploadLockingService} that can be used to check and lock
   *     uploads
   */
  void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException;

  /**
   * Remove the given last amount of bytes from the uploaded data.
   *
   * @param uploadInfo Upload of which to remove the bytes
   * @param byteCount The number of bytes to remove at the end
   */
  void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException;

  /**
   * Terminate completed and unfinished uploads allowing the Server to free up used resources.
   *
   * @param uploadInfo The upload to terminate
   */
  void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException;

  /**
   * Get the expiration period of an upload in milliseconds.
   *
   * @return The number of milliseconds before an upload expires, or null if it cannot expire
   */
  Long getUploadExpirationPeriod();

  /**
   * Set the expiration period after which an in-progress upload expires.
   *
   * @param uploadExpirationPeriod The period in milliseconds
   */
  void setUploadExpirationPeriod(Long uploadExpirationPeriod);

  /**
   * Set the {@link UploadConcatenationService} that this upload storage service should use.
   *
   * @param concatenationService The UploadConcatenationService implementation to use
   */
  void setUploadConcatenationService(UploadConcatenationService concatenationService);

  /**
   * Return the {@link UploadConcatenationService} implementation that this upload service is using.
   *
   * @return The UploadConcatenationService that is being used
   */
  UploadConcatenationService getUploadConcatenationService();

  /**
   * Set an instance if IdFactory to be used for creating identities and extracting them from
   * uploadUris.
   *
   * @param idFactory The {@link UploadIdFactory} to use within this storage service
   */
  void setIdFactory(UploadIdFactory idFactory);
}
