package me.desair.tus.server.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.IOUtils;

/**
 * Service Provider Interface (SPI) for storing, appending, retrieving, and terminating resumable
 * uploads and their associated metadata.
 *
 * <p>Implementations of this interface manage the persistence of binary payload data and {@link
 * UploadInfo} metadata across local filesystems ({@code DiskStorageService}), S3-compatible object
 * storage ({@code S3StorageService}), Azure Blob Storage ({@code AzureBlobStorageService}), or
 * custom storage engines.
 *
 * <h2>1. Upload Lifecycle</h2>
 *
 * <ul>
 *   <li><b>Creation:</b> {@link #create(UploadInfo, String)} allocates initial storage resources
 *       and persists the initial metadata record.
 *   <li><b>Transfer:</b> {@link #append(UploadInfo, InputStream)} streams and appends chunk bytes.
 *       If an append is interrupted (e.g. client disconnect, JVM shutdown, or lock contention), the
 *       service MUST persist all bytes received up to the interruption point, update the offset in
 *       {@link UploadInfo}, and re-throw the exception.
 *   <li><b>Retrieval:</b> {@link #getUploadInfo(UploadId)} and {@link #getUploadInfo(String,
 *       String)} retrieve metadata. {@link #getUploadedBytes(UploadId)} and {@link
 *       #copyUploadTo(UploadInfo, OutputStream)} stream committed data bytes.
 *   <li><b>Termination:</b> {@link #terminateUpload(UploadInfo)} is the authoritative method to
 *       free physical payload bytes, remove metadata, clean up locks, and coordinate deduplication
 *       index cleanup.
 * </ul>
 *
 * <h2>2. Mandatory Locking Service Integration</h2>
 *
 * <p>Concurrency control is mandatory across all storage backends. Every storage service receives
 * an {@link UploadLockingService} instance via {@link
 * #setUploadLockingService(UploadLockingService)} and exposes it via {@link
 * #getUploadLockingService()}. Implementations MUST retain this reference and delegate lock cleanup
 * during {@link #terminateUpload(UploadInfo)} and {@link #cleanupExpiredUploads()}.
 *
 * <h2>3. Checksum Deduplication Support</h2>
 *
 * <p>When upload deduplication is enabled via {@link #setUploadDeduplicationEnabled(boolean)},
 * implementations MUST implement the following critical methods:
 *
 * <ul>
 *   <li>{@link #deduplicate(UploadInfo, ChecksumKey)}: Coordinates cluster-safe deduplication when
 *       an upload completes, typically delegating to {@link
 *       me.desair.tus.server.upload.checksum.DeduplicationService}.
 *   <li>{@link #dataExists(UploadId)}: Checks whether committed physical data exists in storage.
 *       Used by {@link me.desair.tus.server.upload.checksum.DeduplicationService} to self-heal
 *       stale checksum index entries when parent data has been deleted out-of-band.
 *   <li>{@link #deleteData(UploadId)}: Deletes physical committed data without deleting the
 *       upload's {@link UploadInfo} metadata record. Used to free storage for child duplicates
 *       linked to a parent, and to purge retained parent data when the last active child
 *       terminates.
 *   <li><b>Read/Write Safety Rules:</b> Read operations ({@link #getUploadedBytes(UploadId)},
 *       {@link #copyUploadTo(UploadInfo, OutputStream)}) MUST transparently resolve {@link
 *       UploadInfo#getDuplicatesUploadId()} to stream from the parent upload. Mutation operations
 *       ({@link #append(UploadInfo, InputStream)}, {@link #removeLastNumberOfBytes(UploadInfo,
 *       long)}) MUST NEVER resolve duplicates to the parent; they must strictly operate on
 *       child-owned storage.
 * </ul>
 */
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
   * Append the bytes in the given {@link InputStream} to the upload with the given ID starting at
   * the provided offset. This method also updates the {@link UploadInfo} corresponding to this
   * upload. The Upload Storage server should not exceed its max upload size when writing bytes.
   *
   * <p>If the input stream is interrupted or encounters an {@link IOException} during reading (e.g.
   * network disconnect, socket closed, JVM shutdown signal, or lock release interruption), the
   * storage service MUST cleanly persist all bytes received up to the interruption point, update
   * the {@link UploadInfo} offset and metadata accordingly, and re-throw the exception.
   *
   * <p><b>Deduplication Safety:</b> When writing or appending bytes, implementations MUST strictly
   * operate on the target upload's own physical storage files/objects and MUST NEVER resolve {@link
   * UploadInfo#getDuplicatesUploadId()} to modify parent storage.
   *
   * @param upload The ID of the upload
   * @param inputStream The input stream containing the bytes to append
   * @return The new {@link UploadInfo} for this upload
   * @throws IOException If saving bytes or reading from the input stream fails
   * @throws TusException If upload validation fails or storage constraints are violated
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
   * Limit the maximum append payload size to the given value in bytes.
   *
   * @param maxAppendSize The maximum append limit to set
   */
  default void setMaxAppendSize(Long maxAppendSize) {
    // Default no-op for third-party implementations
  }

  /**
   * Get the maximum append payload size configured on this storage service. If not explicitly set,
   * defaults to the maximum upload size limit if set.
   *
   * @return The maximum append size in bytes, or null if no maximum append limit applies
   */
  default Long getMaxAppendSize() {
    long maxUpload = getMaxUploadSize();
    return maxUpload > 0 ? maxUpload : null;
  }

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
   * <p><b>Deduplication Contract:</b> If the upload is a child upload linked to a parent ({@link
   * UploadInfo#getDuplicatesUploadId()} is non-null), implementations MUST dynamically stream data
   * bytes from the parent upload's storage location.
   *
   * @param uploadUri The URI
   * @param ownerKey The owner key of this upload
   * @return an {@link InputStream} containing the bytes of the upload
   */
  InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, UploadNotFoundException;

  /**
   * Get the uploaded bytes corresponding to the given upload ID as a stream.
   *
   * <p><b>Deduplication Contract:</b> If the upload is a child upload linked to a parent ({@link
   * UploadInfo#getDuplicatesUploadId()} is non-null), implementations MUST dynamically stream data
   * bytes from the parent upload's storage location.
   *
   * @param id the ID of the upload
   * @return an {@link InputStream} containing the bytes of the upload
   * @throws IOException When retrieving the bytes from the storage layer fails
   * @throws UploadNotFoundException When the provided id is not linked to an upload
   */
  InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException;

  /**
   * Copy the uploaded bytes to the given output stream.
   *
   * <p><b>Deduplication Contract:</b> If the upload is a child upload linked to a parent ({@link
   * UploadInfo#getDuplicatesUploadId()} is non-null), implementations MUST dynamically copy bytes
   * from the parent upload's storage location.
   *
   * @param info The upload of which we should copy the bytes
   * @param outputStream The output stream where we have to copy the bytes to
   * @throws UploadNotFoundException When the provided upload is not found in storage
   * @throws IOException When reading or writing bytes fails
   */
  default void copyUploadTo(UploadInfo info, OutputStream outputStream)
      throws UploadNotFoundException, IOException {
    Objects.requireNonNull(info, "UploadInfo must not be null");
    Objects.requireNonNull(outputStream, "OutputStream must not be null");

    try (InputStream is = getUploadedBytes(info.getId())) {
      if (is == null) {
        throw new UploadNotFoundException("Upload data not found for ID " + info.getId());
      }
      IOUtils.copyLarge(is, outputStream);
    }
  }

  /**
   * Clean up any upload data that is expired according to the configured expiration time.
   *
   * <p>Storage implementations should use the {@link UploadLockingService} provided via {@link
   * #setUploadLockingService(UploadLockingService)} to check lock status and coordinate lock
   * cleanup.
   *
   * @throws IOException When an I/O error occurs
   */
  default void cleanupExpiredUploads() throws IOException {
    cleanupExpiredUploads(null);
  }

  /**
   * Clean up any upload data that is expired according to the configured expiration time.
   *
   * @param uploadLockingService An {@link UploadLockingService} that can be used to check and lock
   *     uploads
   * @throws IOException When an I/O error occurs
   * @deprecated As of release 2.0.0, replaced by {@link #cleanupExpiredUploads()} in conjunction
   *     with {@link #setUploadLockingService(UploadLockingService)}.
   */
  @Deprecated
  default void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException {
    setUploadLockingService(Utils.getLockingServiceOrDefault(uploadLockingService));
  }

  /**
   * Remove the given last amount of bytes from the uploaded data.
   *
   * <p><b>Deduplication Safety:</b> When truncating bytes, implementations MUST strictly operate on
   * the target upload's own physical storage files/objects and MUST NEVER resolve {@link
   * UploadInfo#getDuplicatesUploadId()} to truncate parent storage.
   *
   * @param uploadInfo Upload of which to remove the bytes
   * @param byteCount The number of bytes to remove at the end
   */
  void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException;

  /**
   * Terminate completed and unfinished uploads allowing the Server to free up used resources.
   *
   * <p>Implementations must:
   *
   * <ul>
   *   <li>Delete physical committed data bytes and any temporary chunk buffers for this upload.
   *   <li>Delete the metadata record ({@code .info}) for this upload.
   *   <li>If upload deduplication is enabled, coordinate index removal or tombstoning via {@link
   *       me.desair.tus.server.upload.checksum.DeduplicationService#removeUploadFromIndex(UploadInfo)}.
   * </ul>
   *
   * @param uploadInfo The upload to terminate
   * @throws UploadNotFoundException When the provided upload is not found in storage
   * @throws IOException When removing data or metadata from the storage layer fails
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

  /**
   * Set whether duplicate file processing based on checksum is enabled.
   *
   * <p>When enabled via {@code TusFileUploadService.withUploadDeduplication(true)}, completed
   * uploads are deduplicated against existing parent uploads with identical checksums. Custom
   * storage engines supporting deduplication must store this flag and coordinate deduplication in
   * {@link #deduplicate(UploadInfo, ChecksumKey)}.
   *
   * @param enabled True if enabled, false otherwise
   */
  default void setUploadDeduplicationEnabled(boolean enabled) {
    // No-op for backward compatibility
  }

  /**
   * Check if duplicate file processing based on checksum is enabled.
   *
   * @return True if enabled, false otherwise
   */
  default boolean isUploadDeduplicationEnabled() {
    return false;
  }

  /**
   * Look up an existing completed upload based on a checksum value and algorithm.
   *
   * @param checksum The checksum value to search for
   * @param algorithm The checksum algorithm used
   * @return The UploadInfo of the existing completed upload, or null if not found
   * @deprecated Use {@link #deduplicate(UploadInfo, ChecksumKey)} instead.
   */
  @Deprecated
  default UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    return null;
  }

  /**
   * Performs thread-safe and cluster-safe deduplication for a completed upload using its normalized
   * checksum key.
   *
   * <p><b>Critical for Deduplication:</b> Implementations supporting upload deduplication MUST
   * override this method (typically delegating to an instance of {@link
   * me.desair.tus.server.upload.checksum.DeduplicationService} backed by a backend-specific {@link
   * me.desair.tus.server.upload.checksum.ChecksumIndex}). When invoked upon upload completion, this
   * method:
   *
   * <ul>
   *   <li>Checks if a completed parent upload with matching checksum already exists.
   *   <li>If a parent exists, links this upload as a child via {@link
   *       UploadInfo#setDuplicatesUploadId(UploadId)} and frees the child's physical data via
   *       {@link #deleteData(UploadId)}.
   *   <li>If no parent exists, atomically registers this upload as the parent in the checksum index
   *       using compare-and-swap (CAS) semantics.
   * </ul>
   *
   * <p>The default implementation returns {@code uploadInfo} unchanged (no deduplication performed)
   * for backward compatibility.
   *
   * @param uploadInfo the upload info of the completed upload
   * @param checksumKey the normalized checksum key
   * @return the resulting {@link UploadInfo} (either deduplicated or registered as parent)
   * @throws IOException on storage I/O error
   */
  default UploadInfo deduplicate(UploadInfo uploadInfo, ChecksumKey checksumKey)
      throws IOException {
    return uploadInfo;
  }

  /**
   * Sets the {@link UploadLockingService} to coordinate locking and lock cleanup.
   *
   * <p><b>Mandatory Locking Integration:</b> An {@link UploadLockingService} is mandatory across
   * all storage backends. Implementations MUST retain this reference and invoke {@link
   * UploadLockingService#cleanupLock(UploadId)} during {@link #terminateUpload(UploadInfo)} and
   * coordinate lock state during {@link #cleanupExpiredUploads()}.
   *
   * @param uploadLockingService the locking service instance (must not be {@code null})
   */
  default void setUploadLockingService(UploadLockingService uploadLockingService) {
    java.util.Objects.requireNonNull(uploadLockingService, "uploadLockingService must not be null");
  }

  /**
   * Gets the configured {@link UploadLockingService}.
   *
   * @return the locking service, or {@code null} if none configured
   */
  default UploadLockingService getUploadLockingService() {
    return null;
  }

  /**
   * Checks whether committed data bytes exist in storage for the specified upload ID.
   *
   * <p><b>Critical for Deduplication:</b> Implementations supporting upload deduplication MUST
   * override this method to accurately verify that physical data bytes exist in storage. The {@link
   * me.desair.tus.server.upload.checksum.DeduplicationService} invokes this check during parent
   * lookup to self-heal stale checksum index entries when parent data has been deleted out-of-band
   * (e.g., via storage lifecycle policies or manual deletion).
   *
   * <p>The default implementation returns {@code false} for backward compatibility. If not
   * overridden in a deduplication-enabled backend, parent uploads will be treated as missing,
   * preventing duplicate linking.
   *
   * @param id The upload ID
   * @return {@code true} if physical data exists for this upload; {@code false} otherwise
   * @throws IOException on storage I/O error
   */
  default boolean dataExists(UploadId id) throws IOException {
    return false;
  }

  /**
   * Deletes the physical committed data bytes (and any partial chunk data) for the specified upload
   * ID without removing the upload's {@link UploadInfo} metadata record.
   *
   * <p><b>Critical for Deduplication:</b> Implementations supporting upload deduplication MUST
   * override this method to free physical payload storage in two lifecycle scenarios:
   *
   * <ul>
   *   <li><b>Child Deduplication:</b> When an upload completes and is identified as a duplicate of
   *       an existing parent, {@link me.desair.tus.server.upload.checksum.DeduplicationService}
   *       invokes this method to delete the child's physical data before linking its metadata to
   *       the parent upload.
   *   <li><b>Last-Child Purge:</b> When a parent upload was previously deleted while children were
   *       active (tombstoned), this method is invoked upon termination of the last active child to
   *       permanently purge the retained parent payload bytes from storage.
   * </ul>
   *
   * <p>The default implementation is a no-op for backward compatibility. If not overridden in a
   * deduplication-enabled backend, duplicate child payloads will remain on disk/cloud storage and
   * tombstoned parent payloads will be leaked.
   *
   * @param id The upload ID
   * @throws IOException on storage I/O error
   */
  default void deleteData(UploadId id) throws IOException {}

  /**
   * Get the minimum append size limit in bytes for a single upload append or creation request.
   *
   * @return The minimum append size in bytes, or null if no minimum append size limit applies
   */
  default Long getMinAppendSize() {
    return null;
  }

  /**
   * Set the minimum append size limit in bytes for a single upload append or creation request.
   *
   * @param minAppendSize The minimum append size in bytes, or null for no limit
   */
  default void setMinAppendSize(Long minAppendSize) {
    // No-op for backward compatibility
  }

  /**
   * Get the minimum total upload representation size in bytes.
   *
   * @return The minimum size in bytes, or null if no minimum size limit applies
   */
  default Long getMinSize() {
    return null;
  }

  /**
   * Set the minimum total upload representation size in bytes.
   *
   * @param minSize The minimum size in bytes, or null for no limit
   */
  default void setMinSize(Long minSize) {
    // No-op for backward compatibility
  }

  /**
   * Set whether JSON serialization should be used for {@link UploadInfo} metadata instead of
   * standard Java object serialization.
   *
   * @param enabled True to enable JSON serialization, false to use default serialization
   */
  default void setJsonSerializationEnabled(boolean enabled) {
    // No-op for backward compatibility
  }

  /**
   * Check if JSON serialization is enabled for {@link UploadInfo} metadata.
   *
   * @return True if JSON serialization is enabled, false otherwise
   */
  default boolean isJsonSerializationEnabled() {
    return false;
  }

  /**
   * Set the thread pool size used for asynchronous background chunk uploading to cloud storage.
   *
   * @param size Number of worker threads
   */
  default void setCloudUploadThreadPoolSize(int size) {
    // Default no-op for non-cloud implementations
  }

  /**
   * Get the thread pool size used for asynchronous background chunk uploading to cloud storage.
   *
   * @return Thread pool size, defaults to 10
   */
  default int getCloudUploadThreadPoolSize() {
    return 10;
  }

  /**
   * Set the timeout for draining in-flight chunks during upload completion or interruption.
   *
   * @param drainTimeout Drain timeout duration
   */
  default void setDrainTimeout(Duration drainTimeout) {
    // Default no-op for non-cloud implementations
  }

  /**
   * Get the timeout for draining in-flight chunks during upload completion or interruption.
   * Defaults to 55 seconds (derived from the 60-second default lock wait timeout minus 5 seconds).
   *
   * @return Drain timeout duration, defaults to 55 seconds
   */
  default Duration getDrainTimeout() {
    return Duration.ofSeconds(55);
  }

  /**
   * Closes any underlying storage resources.
   *
   * @throws IOException If closing fails
   */
  default void close() throws IOException {
    // No-op by default for backward compatibility
  }
}
