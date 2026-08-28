package me.desair.tus.server.upload.s3;

import io.minio.ComposeObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.SourceObject;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.MinAppendSizeNotMetException;
import me.desair.tus.server.exception.MinUploadLengthNotReachedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO S3-backed implementation of {@link UploadStorageService} using the lightweight MinIO Java
 * SDK.
 *
 * <p>Key Design Architecture & S3/MinIO Developer Guide:
 *
 * <ul>
 *   <li><b>Server-Side Object Composition ({@code composeObject})</b>: Instead of assembling
 *       multi-GB uploads locally on disk or in server RAM, completed chunk parts are combined
 *       directly on the S3 storage cluster using S3 server-side object composition. This yields
 *       zero server memory overhead and ultra-fast completion times.
 *   <li><b>Sub-5MB Incomplete Part Buffering</b>: AWS S3 and MinIO require every part chunk of a
 *       multipart upload to be at least 5 MB (5,242,880 bytes), except for the final part. To
 *       handle arbitrarily small client appends (e.g., 64 KB network packets or frequent small
 *       PATCH calls), sub-5MB tail bytes are buffered to S3 as a temporary {@code
 *       <metadataPrefix>/<UploadId>.part} object. When a subsequent PATCH arrives, this leftover
 *       part is fetched, prepended to the incoming payload stream, and processed seamlessly.
 *   <li><b>Dynamic Optimal Part Sizing</b>: Auto-scales part chunk sizes between 5 MB and 5 GB
 *       (capped at S3's maximum limit of 10,000 parts per object).
 *   <li><b>Zero-Byte & Checksum Deduplication Support</b>: Seamlessly manages 0-byte upload
 *       creation and index lookup for instant duplicate file matching.
 * </ul>
 */
public class S3StorageService implements UploadStorageService {

  private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

  // Key Prefixes for S3 object layout separation
  public static final String DEFAULT_OBJECT_PREFIX = "uploads/";
  public static final String DEFAULT_METADATA_PREFIX = "metadata/";
  public static final String DEFAULT_CHECKSUMS_PREFIX = "checksums/";
  public static final String DEFAULT_LOCKS_PREFIX = "locks/";

  // Part Sizing Constraints (per AWS S3 & MinIO specifications)
  private static final long DEFAULT_MIN_PART_SIZE =
      5L * 1024 * 1024; // 5 MB (S3 minimum part limit)
  private static final long DEFAULT_PREFERRED_PART_SIZE =
      50L * 1024 * 1024; // 50 MB (Optimal chunk size)
  private static final long DEFAULT_MAX_PART_SIZE =
      5L * 1024 * 1024 * 1024L; // 5 GB (S3 maximum object/part limit)

  private final MinioClient minioClient;
  private final String bucket;
  private final String objectPrefix;
  private final String metadataPrefix;
  private final String checksumsPrefix;
  private final String locksPrefix;
  private final Path temporaryDirectory;

  private long minPartSize = DEFAULT_MIN_PART_SIZE;
  private long preferredPartSize = DEFAULT_PREFERRED_PART_SIZE;

  private Long maxUploadSize;
  private Long maxAppendSize;
  private Long minAppendSize;
  private Long minSize;
  private Long uploadExpirationPeriod;
  private boolean deduplicationEnabled = false;

  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private UploadConcatenationService concatenationService;

  /**
   * Basic constructor using default object key prefixes and standard system temp directory.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param bucket S3 bucket name
   */
  public S3StorageService(MinioClient minioClient, String bucket) {
    this(
        minioClient,
        bucket,
        DEFAULT_OBJECT_PREFIX,
        DEFAULT_METADATA_PREFIX,
        DEFAULT_CHECKSUMS_PREFIX,
        DEFAULT_LOCKS_PREFIX,
        Paths.get(System.getProperty("java.io.tmpdir")));
  }

  /**
   * Full constructor allowing full customization of object prefixes and local disk buffer path.
   *
   * @param minioClient Pre-configured MinIO Client
   * @param bucket S3 bucket name
   * @param objectPrefix Key prefix for final completed file objects
   * @param metadataPrefix Key prefix for metadata (.info JSON and .part buffer) objects
   * @param checksumsPrefix Key prefix for checksum deduplication index objects
   * @param locksPrefix Key prefix for distributed lock lease objects
   * @param temporaryDirectory Local directory path for staging chunks before S3 upload
   */
  public S3StorageService(
      MinioClient minioClient,
      String bucket,
      String objectPrefix,
      String metadataPrefix,
      String checksumsPrefix,
      String locksPrefix,
      Path temporaryDirectory) {
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.objectPrefix = sanitizePrefix(objectPrefix);
    this.metadataPrefix = sanitizePrefix(metadataPrefix);
    this.checksumsPrefix = sanitizePrefix(checksumsPrefix);
    this.locksPrefix = sanitizePrefix(locksPrefix);
    this.temporaryDirectory =
        temporaryDirectory != null
            ? temporaryDirectory
            : Paths.get(System.getProperty("java.io.tmpdir"));
    try {
      Utils.ensureDirectoryExists(this.temporaryDirectory);
    } catch (IOException e) {
      log.debug("Unable to ensure temporary directory exists: {}", e.getMessage());
    }

    this.concatenationService =
        new S3ConcatenationService(
            this.minioClient, this.bucket, this.objectPrefix, this, this.temporaryDirectory);
  }

  /**
   * Returns the S3 object key for the completed upload data of the given upload info. If the upload
   * was deduplicated, this returns the parent upload's physical S3 object key.
   *
   * @param uploadInfo The upload info object
   * @return The full S3 object key for the uploaded data
   */
  public String getS3ObjectKey(UploadInfo uploadInfo) {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return null;
    }

    // Resolve duplicate child upload dynamically to parent S3 object key per AGENTS.md §7
    if (uploadInfo.getDuplicatesUploadId() != null) {
      return buildObjectKey(uploadInfo.getDuplicatesUploadId());
    }

    if (uploadInfo.getStorageUploadId() != null) {
      return uploadInfo.getStorageUploadId();
    } else {
      return buildObjectKey(uploadInfo.getId());
    }
  }

  /**
   * Return the S3 object key where the uploaded bytes are stored for the given upload URI.
   *
   * @param uploadUri The HTTP request URI of the upload
   * @return The target S3 object key or null if upload not found
   */
  public String getS3ObjectKey(String uploadUri) {
    return getS3ObjectKey(uploadUri, null);
  }

  /**
   * Return the S3 object key where the uploaded bytes are stored for the given upload URI and owner
   * key.
   *
   * @param uploadUri The HTTP request URI of the upload
   * @param ownerKey The owner key of the upload
   * @return The target S3 object key or null if upload not found
   */
  public String getS3ObjectKey(String uploadUri, String ownerKey) {
    try {
      UploadInfo uploadInfo = getUploadInfo(uploadUri, ownerKey);
      return getS3ObjectKey(uploadInfo);
    } catch (IOException e) {
      log.debug("Error retrieving upload info for URI {}", uploadUri, e);
      return null;
    }
  }

  @Override
  public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
    UploadId uploadId = idFactory.readUploadId(uploadUrl);
    if (uploadId == null) {
      return null;
    }
    UploadInfo info = getUploadInfo(uploadId);
    // Enforce strict owner isolation if ownerKey is configured
    if (info != null
        && ((info.getOwnerKey() != null && !info.getOwnerKey().equals(ownerKey))
            || (ownerKey != null && info.getOwnerKey() == null))) {
      return null;
    }
    return info;
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    if (id == null) {
      return null;
    }

    String metadataKey = buildMetadataKey(id);
    String json;
    // Step 1: Read JSON metadata object from S3 (<metadataPrefix>/<UploadId>.info)
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(metadataKey).build())) {
      json = IOUtils.toString(stream, StandardCharsets.UTF_8);
    } catch (ErrorResponseException e) {
      // Return null if key does not exist in S3
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return null;
      }
      throw new IOException("Failed to fetch metadata object from S3 for ID " + id, e);
    } catch (Exception e) {
      throw new IOException("Failed to fetch metadata object from S3 for ID " + id, e);
    }

    // Step 2: Deserialize JSON into UploadInfo instance
    UploadInfo info = UploadInfoJsonSerializer.deserialize(json);
    if (info == null) {
      return null;
    }

    // Step 3: Dynamically compute uploaded byte offset if not explicitly set
    if (info.getOffset() == null) {
      calculateAndSetOffset(info);
    }
    return info;
  }

  @Override
  public String getUploadUri() {
    return idFactory != null ? idFactory.getUploadUri() : "/";
  }

  @Override
  public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
    Objects.requireNonNull(info, "UploadInfo must not be null");

    // Assign new upload ID if missing
    if (info.getId() == null) {
      info.setId(idFactory.createId());
    }
    info.setOwnerKey(ownerKey);
    info.setStorageUploadId(buildObjectKey(info.getId()));

    // Persist initial UploadInfo metadata object (.info) to S3
    try {
      update(info);
    } catch (UploadNotFoundException e) {
      log.error("Unable to update UploadInfo for newly created upload ID " + info.getId(), e);
    }
    return info;
  }

  @Override
  public UploadInfo append(UploadInfo upload, InputStream inputStream)
      throws IOException, TusException {
    // Step 1: Verify upload existence and check configured size limits
    UploadInfo info = fetchAndValidateUpload(upload.getId());
    String objectKey = getS3ObjectKey(info);
    String partObjectKey = buildIncompletePartKey(info.getId());

    // Step 2: If a previous sub-5MB .part buffer exists in S3, download & prepend it to incoming
    // stream
    InputStream streamToRead = prepareStreamWithExistingIncompletePart(partObjectKey, inputStream);

    // Step 3: Process payload stream in optimal chunk parts and upload to S3
    boolean successfullyFinished = false;
    try {
      AppendResult appendResult =
          processPayloadChunks(info, streamToRead, info.getId(), partObjectKey);

      // Step 4: Validate minimum append size constraints if configured
      if (minAppendSize != null && appendResult.totalBytesAppended < minAppendSize) {
        throw new MinAppendSizeNotMetException(
            "Append payload size "
                + appendResult.totalBytesAppended
                + " is below minimum limit "
                + minAppendSize);
      }

      // Step 5: Recalculate total uploaded byte offset across all uploaded part objects in S3
      long newOffset = calculateCurrentOffset(objectKey, info.getId(), partObjectKey);
      info.setOffset(newOffset);
      upload.setOffset(newOffset);

      // Step 6: If all expected bytes are uploaded, compose all part chunks into final S3 object
      finalizeCompletedUploadIfFinished(info, objectKey, info.getId(), appendResult, newOffset);
      update(info);
      successfullyFinished = true;
      return info;
    } finally {
      if (!successfullyFinished) {
        long newOffset = calculateCurrentOffset(objectKey, info.getId(), partObjectKey);
        info.setOffset(newOffset);
        upload.setOffset(newOffset);
        update(info);
      }
    }
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String metadataKey = buildMetadataKey(uploadInfo.getId());
    String json = UploadInfoJsonSerializer.serialize(uploadInfo);
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

    // Upload JSON metadata object to S3
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(metadataKey).stream(
                  new ByteArrayInputStream(jsonBytes), (long) jsonBytes.length, -1L)
              .contentType("application/json")
              .build());
    } catch (Exception e) {
      throw new IOException(
          "Failed to write metadata object to S3 for ID " + uploadInfo.getId(), e);
    }

    // Index checksum for deduplication if upload is completed and deduplication is enabled
    if (isUploadDeduplicationEnabled()
        && uploadInfo.getChecksum() != null
        && !uploadInfo.isUploadInProgress()
        && uploadInfo.getDuplicatesUploadId() == null) {
      putChecksumIndex(
          uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm(), uploadInfo.getId());
    }
  }

  @Override
  public InputStream getUploadedBytes(String uploadUri, String ownerKey)
      throws IOException, UploadNotFoundException {
    UploadInfo info = getUploadInfo(uploadUri, ownerKey);
    if (info == null) {
      throw new UploadNotFoundException("Upload not found for URI " + uploadUri);
    }
    return getUploadedBytes(info.getId());
  }

  @Override
  public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
    UploadInfo info = getUploadInfo(id);
    if (info == null) {
      throw new UploadNotFoundException("Upload with ID " + id + " was not found");
    }

    // Resolve duplicate upload reference to parent upload if deduplicated
    if (info.getDuplicatesUploadId() != null) {
      return getUploadedBytes(info.getDuplicatesUploadId());
    }

    // Handle concatenated upload resolution if applicable
    if (UploadType.CONCATENATED.equals(info.getUploadType()) && info.isUploadInProgress()) {
      if (concatenationService != null) {
        concatenationService.merge(info);
        info = getUploadInfo(id);
      }
    }

    return fetchS3ByteStream(id, info);
  }

  @Override
  public void copyUploadTo(UploadInfo info, OutputStream outputStream)
      throws UploadNotFoundException, IOException {
    try (InputStream is = getUploadedBytes(info.getId())) {
      IOUtils.copy(is, outputStream);
    }
  }

  @Override
  public void cleanupExpiredUploads(UploadLockingService uploadLockingService) throws IOException {
    try {
      // List all metadata objects under metadataPrefix (e.g. metadata/*.info)
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder().bucket(bucket).prefix(metadataPrefix).build());

      for (Result<Item> result : results) {
        Item item = result.get();
        if (item.objectName().endsWith(".info")) {
          String idStr =
              item.objectName()
                  .substring(
                      metadataPrefix.length(), item.objectName().length() - ".info".length());
          UploadId id = new UploadId(idStr);
          UploadInfo info = getUploadInfo(id);

          // Delete expired uploads if not currently locked by an active request
          if (info != null
              && info.isExpired()
              && (uploadLockingService == null || !uploadLockingService.isLocked(id))) {
            terminateUpload(info);
          }
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to cleanup expired S3 uploads", e);
    }
  }

  @Override
  public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException {
    if (uploadInfo == null || byteCount <= 0) {
      return;
    }
    String objectKey = getS3ObjectKey(uploadInfo);
    String partKey = buildIncompletePartKey(uploadInfo.getId());

    long newOffset = Math.max(0L, uploadInfo.getOffset() - byteCount);
    uploadInfo.setOffset(newOffset);
    update(uploadInfo);

    // If final completed object exists in S3, truncate it
    if (objectExists(objectKey)) {
      truncateFromCompletedObject(objectKey, partKey, newOffset);
      return;
    }

    // Otherwise truncate from incomplete .part object
    truncateFromIncompletePart(partKey, byteCount);
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String objectKey = getS3ObjectKey(uploadInfo);
    String metadataKey = buildMetadataKey(uploadInfo.getId());
    String partKey = buildIncompletePartKey(uploadInfo.getId());

    // Delete final object, metadata object, and incomplete part object from S3
    deleteObjectQuietly(objectKey);
    deleteObjectQuietly(metadataKey);
    deleteObjectQuietly(partKey);

    // Delete all temporary part chunk objects (e.g. metadata/<id>.part.00001)
    deleteAllPartObjectsQuietly(uploadInfo.getId());

    // Delete checksum deduplication index object if present
    if (uploadInfo.getChecksum() != null && uploadInfo.getChecksumAlgorithm() != null) {
      deleteObjectQuietly(
          buildChecksumKey(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm()));
    }

    // Delete lock target and stop signal objects
    deleteObjectQuietly(buildLockKey(uploadInfo.getId()));
    deleteObjectQuietly(buildStopKey(uploadInfo.getId()));
  }

  @Override
  public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    if (!isUploadDeduplicationEnabled() || checksum == null || algorithm == null) {
      return null;
    }

    String checksumKey = buildChecksumKey(checksum, algorithm);
    String parentIdStr;
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(checksumKey).build())) {
      parentIdStr = IOUtils.toString(stream, StandardCharsets.UTF_8).trim();
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return null;
      }
      throw new IOException("Failed to read checksum index object from S3", e);
    } catch (Exception e) {
      throw new IOException("Failed to read checksum index object from S3", e);
    }

    UploadId parentId = new UploadId(parentIdStr);
    UploadInfo parentInfo = getUploadInfo(parentId);
    // Self-cleaning: if index points to missing parent upload or object, prune stale index
    if (parentInfo == null || !objectExists(buildObjectKey(parentId))) {
      deleteObjectQuietly(checksumKey);
      return null;
    }

    return parentInfo;
  }

  // CONFIGURATION SETTERS & GETTERS

  @Override
  public void setMaxUploadSize(Long maxUploadSize) {
    this.maxUploadSize = maxUploadSize;
  }

  @Override
  public long getMaxUploadSize() {
    return maxUploadSize != null ? maxUploadSize : 0L;
  }

  @Override
  public void setMaxAppendSize(Long maxAppendSize) {
    this.maxAppendSize = maxAppendSize;
  }

  @Override
  public Long getMaxAppendSize() {
    return maxAppendSize != null ? maxAppendSize : (maxUploadSize != null ? maxUploadSize : null);
  }

  @Override
  public void setMinAppendSize(Long minAppendSize) {
    this.minAppendSize = minAppendSize;
  }

  @Override
  public Long getMinAppendSize() {
    return minAppendSize;
  }

  @Override
  public void setMinSize(Long minSize) {
    this.minSize = minSize;
  }

  @Override
  public Long getMinSize() {
    return minSize;
  }

  @Override
  public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {
    this.uploadExpirationPeriod = uploadExpirationPeriod;
  }

  @Override
  public Long getUploadExpirationPeriod() {
    return uploadExpirationPeriod;
  }

  @Override
  public void setUploadDeduplicationEnabled(boolean enabled) {
    this.deduplicationEnabled = enabled;
  }

  @Override
  public boolean isUploadDeduplicationEnabled() {
    return deduplicationEnabled;
  }

  @Override
  public boolean isJsonSerializationEnabled() {
    return true;
  }

  @Override
  public void setUploadConcatenationService(UploadConcatenationService concatenationService) {
    this.concatenationService = concatenationService;
  }

  @Override
  public UploadConcatenationService getUploadConcatenationService() {
    return concatenationService;
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    if (idFactory != null) {
      this.idFactory = idFactory;
    }
  }

  // PRIVATE HELPER METHODS & S3 PROCESSING LOGIC

  private UploadInfo fetchAndValidateUpload(UploadId uploadId)
      throws UploadNotFoundException, TusException, IOException {
    UploadInfo info = getUploadInfo(uploadId);
    if (info == null) {
      throw new UploadNotFoundException("Upload with ID " + uploadId + " was not found");
    }
    validateUploadLimits(info);
    return info;
  }

  private void validateUploadLimits(UploadInfo info) throws TusException {
    if (info.getLength() != null) {
      if (maxUploadSize != null && maxUploadSize > 0 && info.getLength() > maxUploadSize) {
        throw new MaxUploadLengthExceededException(
            "Upload length " + info.getLength() + " exceeds max limit of " + maxUploadSize);
      }
      if (minSize != null && minSize > 0 && info.getLength() < minSize) {
        throw new MinUploadLengthNotReachedException(
            "Upload length " + info.getLength() + " is below min limit of " + minSize);
      }
    }
  }

  /**
   * Check if a leftover sub-5MB .part buffer object exists from a previous incomplete PATCH
   * request. If found, downloads it to local disk, deletes the .part object from S3, and prepends
   * its bytes to the incoming input stream using {@link SequenceInputStream}.
   */
  private InputStream prepareStreamWithExistingIncompletePart(
      String partObjectKey, InputStream inputStream) throws IOException {
    try {
      StatObjectResponse partHead =
          minioClient.statObject(
              StatObjectArgs.builder().bucket(bucket).object(partObjectKey).build());
      if (partHead != null) {
        InputStream partStream =
            minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(partObjectKey).build());
        File tempPrependedFile =
            File.createTempFile("tus-s3-prep-", ".tmp", temporaryDirectory.toFile());
        tempPrependedFile.deleteOnExit();

        try (FileOutputStream fos = new FileOutputStream(tempPrependedFile)) {
          IOUtils.copy(partStream, fos);
        }
        deleteObjectQuietly(partObjectKey);
        return new SequenceInputStream(new FileInputStream(tempPrependedFile), inputStream);
      }
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        // Normal case: no leftover .part object present in S3
      }
    } catch (Exception ignored) {
    }
    return inputStream;
  }

  /**
   * Reads bytes from the incoming stream into temporary local files of optimal part size (default
   * 50MB). Parts $\ge$ 5MB are uploaded immediately to S3 as part chunk objects. Any trailing chunk
   * under 5MB is saved as a temporary .part object unless it completes the overall upload.
   */
  private AppendResult processPayloadChunks(
      UploadInfo info, InputStream streamToRead, UploadId id, String partObjectKey)
      throws IOException, MaxAppendSizeExceededException {

    List<String> partKeys = fetchExistingPartKeys(id);
    int nextPartNumber = partKeys.size() + 1;
    List<String> allPartKeys = new ArrayList<>(partKeys);

    long optimalPartSize = calcOptimalPartSize(info.getLength() != null ? info.getLength() : 0);
    byte[] buffer = new byte[8192];
    long totalBytesAppended = 0;

    boolean streamFinished = false;
    while (!streamFinished) {
      File tempChunkFile =
          File.createTempFile("tus-s3-chunk-", ".tmp", temporaryDirectory.toFile());
      tempChunkFile.deleteOnExit();

      long chunkBytesWritten = 0;
      IOException readException = null;
      try (FileOutputStream fos = new FileOutputStream(tempChunkFile)) {
        int bytesRead;
        while (chunkBytesWritten < optimalPartSize
            && (bytesRead = streamToRead.read(buffer)) != -1) {
          if (maxAppendSize != null && (totalBytesAppended + bytesRead) > maxAppendSize) {
            boolean deleted = tempChunkFile.delete();
            if (!deleted) {
              log.warn("Failed to delete temp chunk file {}", tempChunkFile.getAbsolutePath());
            }
            throw new MaxAppendSizeExceededException(
                "Append payload exceeded limit of " + maxAppendSize);
          }
          fos.write(buffer, 0, bytesRead);
          chunkBytesWritten += bytesRead;
          totalBytesAppended += bytesRead;
        }

        if (chunkBytesWritten < optimalPartSize) {
          streamFinished = true;
        }
      } catch (IOException e) {
        readException = e;
        streamFinished = true;
      }

      if (chunkBytesWritten == 0) {
        boolean deleted = tempChunkFile.delete();
        if (!deleted) {
          log.warn("Failed to delete temp chunk file {}", tempChunkFile.getAbsolutePath());
        }
        if (readException != null) {
          throw readException;
        }
        break;
      }

      long currentTotalOffset = info.getOffset() + totalBytesAppended;
      boolean isUploadComplete = info.getLength() != null && currentTotalOffset >= info.getLength();

      // AWS S3 / MinIO Rule: Parts must be >= 5 MB unless it's the final part completing the upload
      if (chunkBytesWritten >= minPartSize || (streamFinished && isUploadComplete)) {
        String chunkKey = buildChunkPartKey(id, nextPartNumber);
        uploadChunkToS3(chunkKey, tempChunkFile, chunkBytesWritten);
        allPartKeys.add(chunkKey);
        nextPartNumber++;
      } else {
        // Store sub-5MB tail chunk as temporary .part object in S3 for subsequent appends
        storeIncompletePartToS3(partObjectKey, tempChunkFile, chunkBytesWritten);
      }

      if (readException != null) {
        throw readException;
      }
    }

    return new AppendResult(totalBytesAppended, allPartKeys);
  }

  private void uploadChunkToS3(String chunkKey, File tempChunkFile, long chunkLength)
      throws IOException {
    try (FileInputStream fis = new FileInputStream(tempChunkFile)) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(chunkKey).stream(fis, chunkLength, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed to upload part chunk to S3 key " + chunkKey, e);
    } finally {
      boolean deleted = tempChunkFile.delete();
      if (!deleted) {
        log.warn("Failed to delete temp chunk file {}", tempChunkFile.getAbsolutePath());
      }
    }
  }

  private void storeIncompletePartToS3(String partObjectKey, File tempChunkFile, long chunkLength)
      throws IOException {
    try (FileInputStream fis = new FileInputStream(tempChunkFile)) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(partObjectKey).stream(fis, chunkLength, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed to write incomplete part object to S3 key " + partObjectKey, e);
    } finally {
      boolean deleted = tempChunkFile.delete();
      if (!deleted) {
        log.warn("Failed to delete temp chunk file {}", tempChunkFile.getAbsolutePath());
      }
    }
  }

  /**
   * When all expected bytes have been received, this method combines all part chunk objects in S3
   * into the final destination object key using MinIO's {@code composeObject} API.
   */
  private void finalizeCompletedUploadIfFinished(
      UploadInfo info, String objectKey, UploadId id, AppendResult appendResult, long newOffset)
      throws IOException {

    if (info.getLength() != null && newOffset >= info.getLength()) {
      List<String> partKeys = fetchExistingPartKeys(id);

      // If leftover sub-5MB .part exists, save it as final part chunk
      String leftoverPartKey = buildIncompletePartKey(id);
      if (objectExists(leftoverPartKey)) {
        int nextPartNum = partKeys.size() + 1;
        String finalChunkKey = buildChunkPartKey(id, nextPartNum);
        try (InputStream stream =
            minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(leftoverPartKey).build())) {
          byte[] bytes = IOUtils.toByteArray(stream);
          minioClient.putObject(
              PutObjectArgs.builder().bucket(bucket).object(finalChunkKey).stream(
                      new ByteArrayInputStream(bytes), (long) bytes.length, -1L)
                  .build());
          partKeys.add(finalChunkKey);
        } catch (Exception e) {
          throw new IOException("Failed to finalize incomplete part for ID " + id, e);
        }
        deleteObjectQuietly(leftoverPartKey);
      }

      if (!partKeys.isEmpty()) {
        List<SourceObject> sources = new ArrayList<>();
        for (String pk : partKeys) {
          sources.add(SourceObject.builder().bucket(bucket).object(pk).build());
        }

        // Perform S3 server-side object composition (composeObject)
        try {
          minioClient.composeObject(
              ComposeObjectArgs.builder()
                  .bucket(bucket)
                  .object(objectKey)
                  .sources(sources)
                  .build());
        } catch (Exception e) {
          throw new IOException("Failed to compose final object " + objectKey, e);
        }

        // Clean up temporary part chunk objects in S3
        for (String pk : partKeys) {
          deleteObjectQuietly(pk);
        }
      }

      // Add checksum index if deduplication is enabled
      if (isUploadDeduplicationEnabled()
          && info.getChecksum() != null
          && info.getDuplicatesUploadId() == null) {
        putChecksumIndex(info.getChecksum(), info.getChecksumAlgorithm(), info.getId());
      }
    }
  }

  private InputStream fetchS3ByteStream(UploadId id, UploadInfo info)
      throws UploadNotFoundException {
    String objectKey = getS3ObjectKey(info);

    try {
      // Step 1: Attempt to read from completed object key in S3
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        // Step 2: Fallback to reading from incomplete .part object if upload is in-progress
        String partKey = buildIncompletePartKey(id);
        try {
          return minioClient.getObject(
              GetObjectArgs.builder().bucket(bucket).object(partKey).build());
        } catch (ErrorResponseException ex) {
          // If the incomplete .part object is also missing (NoSuchKey) and the offset is
          // zero or null, it means no bytes have been uploaded yet (e.g. immediately after
          // creation). In this case, we return an empty stream rather than throwing an exception.
          if (S3Utils.parseErrorResponse(ex) == S3ErrorType.NO_SUCH_KEY) {
            if (info != null && (info.getOffset() == null || info.getOffset() == 0L)) {
              return new ByteArrayInputStream(new byte[0]);
            }
          }
          log.debug("Failed to read incomplete .part object {}", partKey, ex);
        } catch (Exception exception) {
          log.debug("Failed to read incomplete .part object {}", partKey, exception);
        }
      }
      throw new UploadNotFoundException("Uploaded bytes object not found for ID " + id);
    } catch (Exception e) {
      throw new UploadNotFoundException("Uploaded bytes object not found for ID " + id);
    }
  }

  private void truncateFromCompletedObject(String objectKey, String partKey, long newOffset)
      throws IOException {
    if (newOffset > 0) {
      try (InputStream objStream =
          minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
        byte[] remainingBytes = new byte[(int) newOffset];
        IOUtils.readFully(objStream, remainingBytes);
        minioClient.putObject(
            PutObjectArgs.builder().bucket(bucket).object(partKey).stream(
                    new ByteArrayInputStream(remainingBytes), (long) remainingBytes.length, -1L)
                .build());
      } catch (Exception e) {
        throw new IOException("Failed to truncate completed object key " + objectKey, e);
      }
    }
    deleteObjectQuietly(objectKey);
  }

  private void truncateFromIncompletePart(String partKey, long byteCount) {
    try {
      StatObjectResponse head =
          minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(partKey).build());
      long partSize = head.size();

      if (byteCount >= partSize) {
        deleteObjectQuietly(partKey);
      } else {
        try (InputStream partStream =
            minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(partKey).build())) {
          byte[] bytes = IOUtils.toByteArray(partStream);
          int newLength = (int) (bytes.length - byteCount);
          byte[] remaining = Arrays.copyOf(bytes, newLength);

          minioClient.putObject(
              PutObjectArgs.builder().bucket(bucket).object(partKey).stream(
                      new ByteArrayInputStream(remaining), (long) remaining.length, -1L)
                  .build());
        }
      }
    } catch (ErrorResponseException ignored) {
    } catch (Exception e) {
      log.debug("Error truncating incomplete part object {}", partKey, e);
    }
  }

  private void calculateAndSetOffset(UploadInfo info) {
    if (info == null || info.getId() == null) {
      return;
    }
    String objectKey = getS3ObjectKey(info);
    String partKey = buildIncompletePartKey(info.getId());

    long offset = calculateCurrentOffset(objectKey, info.getId(), partKey);
    info.setOffset(offset);
  }

  private long calculateCurrentOffset(String objectKey, UploadId id, String partKey) {
    long offset = 0;

    if (objectExists(objectKey)) {
      try {
        StatObjectResponse head =
            minioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        offset += head.size();
      } catch (Exception ignored) {
      }
    }

    List<String> partKeys = fetchExistingPartKeys(id);
    for (String pk : partKeys) {
      try {
        StatObjectResponse stat =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(pk).build());
        offset += stat.size();
      } catch (Exception ignored) {
      }
    }

    if (!partKeys.contains(partKey)) {
      try {
        StatObjectResponse partHead =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(partKey).build());
        if (partHead != null) {
          offset += partHead.size();
        }
      } catch (ErrorResponseException ignored) {
      } catch (Exception e) {
        log.debug("Error reading head for incomplete part object {}", partKey, e);
      }
    }

    return offset;
  }

  private List<String> fetchExistingPartKeys(UploadId id) {
    String prefix = metadataPrefix + id.toString() + ".part.";
    List<String> partKeys = new ArrayList<>();
    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix(prefix).build());
      for (Result<Item> res : results) {
        partKeys.add(res.get().objectName());
      }
    } catch (Exception ignored) {
    }
    return partKeys;
  }

  private void deleteAllPartObjectsQuietly(UploadId id) {
    List<String> partKeys = fetchExistingPartKeys(id);
    for (String pk : partKeys) {
      deleteObjectQuietly(pk);
    }
  }

  private long calcOptimalPartSize(long totalSize) {
    long partSize = preferredPartSize;
    if (totalSize > 0 && totalSize / partSize >= 10000) {
      partSize = (totalSize / 10000) + 1;
    }
    return Math.max(minPartSize, Math.min(partSize, DEFAULT_MAX_PART_SIZE));
  }

  private void putChecksumIndex(String checksum, ChecksumAlgorithm algorithm, UploadId parentId) {
    String key = buildChecksumKey(checksum, algorithm);
    try {
      byte[] parentIdBytes = parentId.toString().getBytes(StandardCharsets.UTF_8);
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(key).stream(
                  new ByteArrayInputStream(parentIdBytes), (long) parentIdBytes.length, -1L)
              .build());
    } catch (Exception e) {
      log.warn("Failed to write checksum index object to S3 key {}", key, e);
    }
  }

  private boolean objectExists(String key) {
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
      return true;
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return false;
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  private void deleteObjectQuietly(String key) {
    if (key == null) {
      return;
    }
    try {
      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {}", key, e);
    }
  }

  private String sanitizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    String result = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    return result.endsWith("/") ? result : result + "/";
  }

  private String buildObjectKey(UploadId id) {
    return objectPrefix + id.toString();
  }

  private String buildMetadataKey(UploadId id) {
    return metadataPrefix + id.toString() + ".info";
  }

  private String buildIncompletePartKey(UploadId id) {
    return metadataPrefix + id.toString() + ".part";
  }

  private String buildChunkPartKey(UploadId id, int partNumber) {
    return metadataPrefix + id.toString() + ".part." + String.format("%05d", partNumber);
  }

  private String buildChecksumKey(String checksum, ChecksumAlgorithm algorithm) {
    String algorithmName = algorithm != null ? algorithm.getTusName().toLowerCase() : "unknown";
    return checksumsPrefix + algorithmName + "/" + checksum;
  }

  private String buildLockKey(UploadId id) {
    return locksPrefix + id.toString() + ".lock";
  }

  private String buildStopKey(UploadId id) {
    return locksPrefix + id.toString() + ".stop";
  }

  private static class AppendResult {
    final long totalBytesAppended;
    final List<String> allPartKeys;

    AppendResult(long totalBytesAppended, List<String> allPartKeys) {
      this.totalBytesAppended = totalBytesAppended;
      this.allPartKeys = allPartKeys;
    }
  }
}
