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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import me.desair.tus.server.upload.util.AsyncChunkUploader;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
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
  public static final long DEFAULT_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MB (S3 minimum part limit)
  public static final long DEFAULT_PREFERRED_PART_SIZE =
      8L * 1024 * 1024; // 8 MB (Optimal chunk size aligned with Azure)
  public static final long DEFAULT_MAX_PART_SIZE =
      5L * 1024 * 1024 * 1024L; // 5 GB (S3 maximum object/part limit)
  public static final int MAX_PARTS_PER_UPLOAD = 10_000;

  private final MinioClient minioClient;
  private final String bucket;
  private final String objectPrefix;
  private final String metadataPrefix;
  private final String checksumsPrefix;
  private final String locksPrefix;
  private final Path temporaryDirectory;

  private long minPartSize = DEFAULT_MIN_PART_SIZE;
  private long preferredPartSize = DEFAULT_PREFERRED_PART_SIZE;

  private int cloudUploadThreadPoolSize = 10;
  private final ThreadPoolExecutor uploadExecutor;

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
      // Clean up orphaned local temporary chunk files left from prior JVM crashes/aborts
      Utils.cleanupTempFiles(this.temporaryDirectory, "tus-s3-chunk-*.tmp", 24L * 3600_000L);
      Utils.cleanupTempFiles(this.temporaryDirectory, "tus-s3-prep-*.tmp", 24L * 3600_000L);
    } catch (IOException e) {
      log.debug("Unable to ensure temporary directory exists: {}", e.getMessage());
    }

    AtomicInteger threadNum = new AtomicInteger(1);
    this.uploadExecutor =
        new ThreadPoolExecutor(
            cloudUploadThreadPoolSize,
            cloudUploadThreadPoolSize,
            60L,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            r -> {
              Thread t = new Thread(r, "tus-s3-upload-" + threadNum.getAndIncrement());
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

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
    if (upload.getLength() != null && info.getLength() == null) {
      info.setLength(upload.getLength());
    }
    String objectKey = getS3ObjectKey(info);
    String partObjectKey = buildIncompletePartKey(info.getId());

    // Step 2: If a previous sub-5MB .part buffer exists in S3, download & prepend it to incoming
    // stream. Also roll back any existing sub-5MB numbered parts (e.g. from prior aborted
    // sessions).
    PreparedStream preparedStream =
        prepareStreamWithExistingIncompletePart(info.getId(), partObjectKey, inputStream);

    // Validate that the upload has not exceeded S3's 10,000 multipart parts ceiling
    validateRemainingPartBudget(info, preparedStream.remainingPartKeys.size());

    // Step 3: Process payload stream in optimal chunk parts and upload to S3
    boolean successfullyFinished = false;
    try {
      AppendResult appendResult =
          processPayloadChunks(info, preparedStream, info.getId(), partObjectKey);

      // Step 4: Recalculate total uploaded byte offset across all uploaded part objects in S3.
      // S3 listObjects can exhibit eventual consistency; take the maximum of remote parts query
      // and locally verified stream progression to ensure newOffset accurately reflects bytes
      // successfully written.
      long calculatedOffset =
          calculateCurrentOffset(objectKey, info.getId(), partObjectKey, info.getLength());
      long currentTotalOffset =
          (info.getOffset() != null ? info.getOffset() : 0L)
              + appendResult.totalBytesAppended
              - preparedStream.prependedBytes;
      long newOffset = Math.max(calculatedOffset, currentTotalOffset);
      info.setOffset(newOffset);
      upload.setOffset(newOffset);

      // Step 5: Validate minimum append size constraints if configured
      // Subtract prependedBytes so minAppendSize accurately measures the payload transferred
      // in THIS request rather than earlier buffered bytes.
      // Per RUFH §4.1.4: "This limit does not apply to upload creation requests with no content,
      // or to requests completing the upload by including the Upload-Complete: ?1 header field."
      boolean isCompletingOrEmpty =
          !info.isUploadInProgress() || (info.getLength() != null && newOffset >= info.getLength());
      long requestPayloadAppended =
          Math.max(0L, appendResult.totalBytesAppended - preparedStream.prependedBytes);
      if (minAppendSize != null && !isCompletingOrEmpty && requestPayloadAppended < minAppendSize) {
        throw new MinAppendSizeNotMetException(
            "Append payload size "
                + requestPayloadAppended
                + " is below minimum limit "
                + minAppendSize);
      }

      // Step 6: If all expected bytes are uploaded, compose all part chunks into final S3 object
      finalizeCompletedUploadIfFinished(info, objectKey, info.getId(), appendResult, newOffset);
      update(info);
      successfullyFinished = true;
      return info;
    } finally {
      if (!successfullyFinished) {
        long newOffset =
            calculateCurrentOffset(objectKey, info.getId(), partObjectKey, info.getLength());
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
    // 1. Clean up orphaned local temporary chunk files
    Utils.cleanupTempFiles(this.temporaryDirectory, "tus-s3-chunk-*.tmp", 24L * 3600_000L);
    Utils.cleanupTempFiles(this.temporaryDirectory, "tus-s3-prep-*.tmp", 24L * 3600_000L);

    try {
      // 2. List all metadata objects under metadataPrefix (e.g. metadata/*.info)
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

          // Per Tus Expiration extension specification, only UNFINISHED uploads are expired:
          // "The Expiration extension is used to indicate when an unfinished upload will be
          // terminated by the Server."
          if (info != null
              && info.isUploadInProgress()
              && info.isExpired()
              && (uploadLockingService == null || !uploadLockingService.isLocked(id))) {
            terminateUpload(info);
          }
        }
      }

      // 3. Prune orphaned checksum index entries pointing to missing or expired uploads
      pruneOrphanedChecksumIndices();
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

  /**
   * Scans and prunes orphaned checksum deduplication index objects in S3 whose parent upload or
   * final data object no longer exists (e.g. pruned by external S3 lifecycle rules).
   */
  private void pruneOrphanedChecksumIndices() {
    // 1. Skip scanning if deduplication is disabled; no checksum indices are generated or
    // referenced
    if (!isUploadDeduplicationEnabled()) {
      return;
    }
    try {
      // 2. Query all index objects located under the deduplication prefix (e.g.
      // checksums/<algo>/<hash>)
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket)
                  .prefix(checksumsPrefix)
                  .recursive(true)
                  .build());
      for (Result<Item> result : results) {
        Item item = result.get();
        if (!item.isDir()) {
          try (InputStream stream =
              minioClient.getObject(
                  GetObjectArgs.builder().bucket(bucket).object(item.objectName()).build())) {
            // 3. Read the parent upload ID string stored as plain text inside the checksum index
            // object
            String parentIdStr = IOUtils.toString(stream, StandardCharsets.UTF_8).trim();
            UploadId parentId = new UploadId(parentIdStr);

            // 4. Verify whether the parent upload's metadata (.info) still exists and is accessible
            UploadInfo parentInfo = getUploadInfo(parentId);

            // 5. Verify whether the parent upload's final data object exists in S3 storage
            // 6. If the parent upload or its data object no longer exists (e.g. expired, deleted,
            // or purged
            // by S3 lifecycle rules), prune the orphaned index entry to keep storage clean
            if (parentInfo == null || !objectExists(buildObjectKey(parentId))) {
              log.debug("Pruning orphaned S3 checksum index object {}", item.objectName());
              deleteObjectQuietly(item.objectName());
            }
          } catch (Exception ignored) {
            // Ignore individual object read errors to allow full sweep of remaining index entries
          }
        }
      }
    } catch (Exception e) {
      log.debug("Failed to prune orphaned S3 checksum indices: {}", e.getMessage());
    }
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

  @Override
  public void setCloudUploadThreadPoolSize(int size) {
    if (size <= 0) {
      throw new IllegalArgumentException(
          "The cloud upload thread pool size must be greater than 0");
    }
    this.cloudUploadThreadPoolSize = size;
    if (size > uploadExecutor.getMaximumPoolSize()) {
      uploadExecutor.setMaximumPoolSize(size);
      uploadExecutor.setCorePoolSize(size);
    } else {
      uploadExecutor.setCorePoolSize(size);
      uploadExecutor.setMaximumPoolSize(size);
    }
  }

  @Override
  public int getCloudUploadThreadPoolSize() {
    return cloudUploadThreadPoolSize;
  }

  /**
   * Set the preferred chunk part size in bytes used when buffering and uploading parts to S3.
   *
   * @param preferredPartSize Part size in bytes (must be between 5 MB and 5 GB)
   */
  public void setPreferredPartSize(long preferredPartSize) {
    if (preferredPartSize < minPartSize || preferredPartSize > DEFAULT_MAX_PART_SIZE) {
      throw new IllegalArgumentException(
          "Preferred part size must be between "
              + minPartSize
              + " and "
              + DEFAULT_MAX_PART_SIZE
              + " bytes");
    }
    this.preferredPartSize = preferredPartSize;
  }

  /**
   * Return the preferred chunk part size in bytes used when buffering and uploading parts to S3.
   *
   * @return Preferred part size in bytes
   */
  public long getPreferredPartSize() {
    return preferredPartSize;
  }

  @Override
  public void close() throws IOException {
    uploadExecutor.shutdown();
    try {
      if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        uploadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      uploadExecutor.shutdownNow();
      Thread.currentThread().interrupt();
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
   * Checks if an incomplete sub-5MB {@code .part} buffer object exists in S3 from a previous
   * interrupted or paused request. If present, downloads its bytes and prepends them to the
   * incoming stream.
   *
   * <p>Additionally, if prior paused sessions promoted a sub-5MB chunk to a numbered part (e.g.
   * {@code .part.00001}), this method dynamically rolls back that sub-5MB chunk from S3 into the
   * stream buffer so that subsequent appends can grow the chunk past AWS S3's 5 MB minimum part
   * size.
   *
   * @param id The upload identifier
   * @param partObjectKey The S3 object key for the incomplete .part buffer
   * @param inputStream The incoming request input stream
   * @return {@link PreparedStream} containing the combined stream and prepended byte count
   * @throws IOException If reading or downloading from S3 fails
   */
  private PreparedStream prepareStreamWithExistingIncompletePart(
      UploadId id, String partObjectKey, InputStream inputStream) throws IOException {
    long prependedBytes = 0L;
    InputStream combinedStream = inputStream;

    // 1. Check for leftover incomplete sub-5MB .part buffer object
    try {
      StatObjectResponse partHead =
          minioClient.statObject(
              StatObjectArgs.builder().bucket(bucket).object(partObjectKey).build());
      if (partHead != null && partHead.size() > 0) {
        try (InputStream partStream =
            minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(partObjectKey).build())) {
          byte[] partBytes = IOUtils.toByteArray(partStream);
          prependedBytes += partBytes.length;
          combinedStream =
              new SequenceInputStream(new ByteArrayInputStream(partBytes), combinedStream);
        }
        deleteObjectQuietly(partObjectKey);
      }
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) != S3ErrorType.NO_SUCH_KEY) {
        log.debug("Error checking incomplete part object {}: {}", partObjectKey, e.getMessage());
      }
    } catch (Exception e) {
      log.debug("Unexpected error reading incomplete part {}: {}", partObjectKey, e.getMessage());
    }

    // 2. Roll back any existing sub-5MB numbered parts (e.g. from prior aborted or paused uploads)
    List<String> existingPartKeys = fetchExistingPartKeys(id);
    if (!existingPartKeys.isEmpty()) {
      String lastPartKey = existingPartKeys.get(existingPartKeys.size() - 1);
      try {
        StatObjectResponse stat =
            minioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(lastPartKey).build());
        if (stat.size() < minPartSize) {
          log.info(
              "Rolling back sub-5MB numbered part {} (size: {}) into stream buffer",
              lastPartKey,
              stat.size());
          try (InputStream lastPartStream =
              minioClient.getObject(
                  GetObjectArgs.builder().bucket(bucket).object(lastPartKey).build())) {
            byte[] lastPartBytes = IOUtils.toByteArray(lastPartStream);
            prependedBytes += lastPartBytes.length;
            combinedStream =
                new SequenceInputStream(new ByteArrayInputStream(lastPartBytes), combinedStream);
          }
          deleteObjectQuietly(lastPartKey);
          existingPartKeys.remove(existingPartKeys.size() - 1);
        }
      } catch (Exception e) {
        log.debug("Error inspecting last part key {}: {}", lastPartKey, e.getMessage());
      }
    }

    return new PreparedStream(combinedStream, prependedBytes, existingPartKeys);
  }

  /**
   * Reads bytes from the incoming stream into temporary local files of optimal part size (default
   * 8MB). Parts &ge; 5MB are uploaded asynchronously to S3 via {@link AsyncChunkUploader},
   * overlapping client stream reading with cloud upload. Any trailing chunk under 5MB is saved as a
   * temporary .part object unless it completes the overall upload.
   */
  private AppendResult processPayloadChunks(
      UploadInfo info, PreparedStream preparedStream, UploadId id, String partObjectKey)
      throws IOException, MaxAppendSizeExceededException {

    List<String> allPartKeys = new ArrayList<>(preparedStream.remainingPartKeys);
    int nextPartNumber = allPartKeys.size() + 1;
    InputStream streamToRead = preparedStream.stream;

    long optimalPartSize = calcOptimalPartSize(info.getLength());
    byte[] buffer = new byte[8192];
    long totalBytesAppended = 0;

    // Calibrate base offset: info.getOffset() already includes prependedBytes from prior writes.
    // Subtracting prependedBytes ensures currentTotalOffset accurately tracks progress from
    // baseOffset.
    long baseOffset = info.getOffset() - preparedStream.prependedBytes;

    boolean streamFinished = false;
    MaxAppendSizeExceededException maxAppendSizeException = null;
    IOException readException = null;

    List<String> plannedPartKeys = new ArrayList<>();

    try (AsyncChunkUploader uploader = new AsyncChunkUploader(uploadExecutor)) {
      while (!streamFinished) {
        File tempChunkFile =
            Files.createTempFile(temporaryDirectory, "tus-s3-chunk-", ".tmp").toFile();
        // Do not call tempChunkFile.deleteOnExit() here. In high-throughput long-running services,
        // deleteOnExit() registers entries in a static JVM set that cannot be garbage collected,
        // creating an unbounded memory leak. Temp files are deleted in try-finally blocks below.

        long chunkBytesWritten = 0;
        boolean handedOff = false;
        try {
          try (FileOutputStream fos = new FileOutputStream(tempChunkFile)) {
            int bytesRead;
            while (chunkBytesWritten < optimalPartSize
                && (bytesRead = streamToRead.read(buffer)) != -1) {
              long requestBytesSoFar =
                  (totalBytesAppended + bytesRead) - preparedStream.prependedBytes;
              if (maxAppendSize != null && requestBytesSoFar > maxAppendSize) {
                // If maxAppendSize is exceeded, do not discard tempChunkFile immediately.
                // If preparedStream had prepended bytes from a previous incomplete .part,
                // discarding
                // tempChunkFile would permanently lose those bytes. Instead, stop reading and
                // record
                // maxAppendSizeException so the bytes currently in tempChunkFile are flushed to S3
                // and UploadInfo offset is accurately preserved.
                maxAppendSizeException =
                    new MaxAppendSizeExceededException(
                        "Append payload exceeded limit of " + maxAppendSize);
                streamFinished = true;
                break;
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
            break;
          }

          // Base offset plus total bytes appended accurately measures uploaded progress without
          // double-counting
          long currentTotalOffset = baseOffset + totalBytesAppended;

          // Interruption Guard: If an IOException or limit exception occurred (e.g. client pause or
          // connection drop),
          // the chunk must NEVER be considered complete, preventing sub-5MB chunks from being
          // promoted.
          boolean isUploadComplete =
              readException == null
                  && maxAppendSizeException == null
                  && info.getLength() != null
                  && currentTotalOffset >= info.getLength();

          // Validate remaining part capacity before allocating next S3 part number
          if (allPartKeys.size() + plannedPartKeys.size() >= MAX_PARTS_PER_UPLOAD
              || nextPartNumber > MAX_PARTS_PER_UPLOAD) {
            maxAppendSizeException =
                new MaxAppendSizeExceededException(
                    "Upload has reached the maximum allowed S3 limit of "
                        + MAX_PARTS_PER_UPLOAD
                        + " parts.");
            if (chunkBytesWritten > 0) {
              int confirmedCount = uploader.drainAndComplete(4000);
              allPartKeys.addAll(plannedPartKeys.subList(0, confirmedCount));
              storeIncompletePartToS3(partObjectKey, tempChunkFile, chunkBytesWritten);
              handedOff = true;
            }
            streamFinished = true;
            break;
          }

          // AWS S3 / MinIO Rule: Parts must be >= 5 MB unless it's the final part completing the
          // upload
          if (chunkBytesWritten >= minPartSize) {
            // Full part chunk (>= 5 MB): Submit to AsyncChunkUploader pipeline for background
            // upload
            String chunkKey = buildChunkPartKey(id, nextPartNumber++);
            plannedPartKeys.add(chunkKey);
            long bytesToUpload = chunkBytesWritten;
            uploader.submitChunk(
                tempChunkFile,
                bytesToUpload,
                chunkKey,
                () -> uploadChunkToS3(chunkKey, tempChunkFile, bytesToUpload));
            handedOff = true;

          } else if (streamFinished && isUploadComplete) {
            // Sub-5MB final chunk that completes the overall upload:
            // First drain all preceding parts in the pipeline
            int confirmedCount = uploader.drainAndComplete(4000);
            allPartKeys.addAll(plannedPartKeys.subList(0, confirmedCount));

            String chunkKey = buildChunkPartKey(id, nextPartNumber++);
            uploadChunkToS3(chunkKey, tempChunkFile, chunkBytesWritten);
            allPartKeys.add(chunkKey);
            handedOff = true;

          } else {
            // Sub-5MB incomplete chunk (e.g. upload paused midway or interrupted):
            // First drain all preceding parts in the pipeline
            int confirmedCount = uploader.drainAndComplete(4000);
            allPartKeys.addAll(plannedPartKeys.subList(0, confirmedCount));

            storeIncompletePartToS3(partObjectKey, tempChunkFile, chunkBytesWritten);
            handedOff = true;
          }

        } finally {
          if (!handedOff) {
            FileUtils.deleteQuietly(tempChunkFile);
          }
        }
      }

      // Drain any remaining in-flight chunks in the pipeline
      int confirmedCount = uploader.drainAndComplete(4000);
      int previouslyConfirmed = allPartKeys.size() - preparedStream.remainingPartKeys.size();
      if (confirmedCount > previouslyConfirmed) {
        allPartKeys.clear();
        allPartKeys.addAll(preparedStream.remainingPartKeys);
        allPartKeys.addAll(plannedPartKeys.subList(0, confirmedCount));
      }
    }

    if (readException != null) {
      throw readException;
    }
    if (maxAppendSizeException != null) {
      throw maxAppendSizeException;
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
      FileUtils.deleteQuietly(tempChunkFile);
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
      FileUtils.deleteQuietly(tempChunkFile);
    }
  }

  /**
   * When all expected bytes have been received, this method combines all part chunk objects in S3
   * into the final destination object key using MinIO's {@code composeObject} API.
   *
   * <p>If non-final parts are smaller than AWS S3's 5 MB minimum threshold (e.g. from multi-pause
   * uploads), this method automatically falls back to streaming concatenation using {@link
   * #mergeUsingStreamingReupload(String, List, long)}, guaranteeing successful upload finalization.
   */
  private void finalizeCompletedUploadIfFinished(
      UploadInfo info, String objectKey, UploadId id, AppendResult appendResult, long newOffset)
      throws IOException {

    if (info.getLength() != null && newOffset >= info.getLength()) {
      List<String> partKeys = fetchExistingPartKeys(id);

      long existingPartsTotalSize = 0L;
      for (String pk : partKeys) {
        try {
          StatObjectResponse stat =
              minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(pk).build());
          existingPartsTotalSize += stat.size();
        } catch (Exception ignored) {
        }
      }

      // If leftover sub-5MB .part exists, apply arithmetic budget invariants
      String leftoverPartKey = buildIncompletePartKey(id);
      if (objectExists(leftoverPartKey)) {
        long leftoverSize = 0L;
        try {
          StatObjectResponse partHead =
              minioClient.statObject(
                  StatObjectArgs.builder().bucket(bucket).object(leftoverPartKey).build());
          if (partHead != null) {
            leftoverSize = partHead.size();
          }
        } catch (Exception ignored) {
        }

        // Arithmetic Decision Matrix:
        // Case 1: Numbered parts already satisfy the entire upload length.
        // The .part buffer is an orphaned/stale duplicate (e.g. from prior pause).
        // MUST DELETE to prevent byte duplication.
        if (existingPartsTotalSize >= info.getLength()) {
          log.info(
              "Purging stale incomplete part {} (size: {}) as numbered parts already cover upload length ({})",
              leftoverPartKey,
              leftoverSize,
              info.getLength());
          deleteObjectQuietly(leftoverPartKey);

        } else if (existingPartsTotalSize + leftoverSize == info.getLength()) {
          // Case 2: Numbered parts plus this leftover buffer match the expected length exactly.
          // This is a legitimate new tail written during this request.
          // MUST PROMOTE to the final numbered part.
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

        } else {
          // Case 3: Oversized or inconsistent leftover buffer.
          log.warn(
              "Discarding inconsistent incomplete part {} (size: {}, numbered parts: {}, total length: {})",
              leftoverPartKey,
              leftoverSize,
              existingPartsTotalSize,
              info.getLength());
          deleteObjectQuietly(leftoverPartKey);
        }
      }

      if (!partKeys.isEmpty()) {
        boolean canUseServerSideCompose = true;
        if (partKeys.size() > 1) {
          // AWS S3 requires all parts except the last to be >= 5 MB (5,242,880 bytes) for
          // composeObject
          for (int i = 0; i < partKeys.size() - 1; i++) {
            try {
              StatObjectResponse stat =
                  minioClient.statObject(
                      StatObjectArgs.builder().bucket(bucket).object(partKeys.get(i)).build());
              if (stat.size() < minPartSize) {
                canUseServerSideCompose = false;
                break;
              }
            } catch (Exception e) {
              canUseServerSideCompose = false;
              break;
            }
          }
        }

        if (canUseServerSideCompose) {
          try {
            List<SourceObject> sources = new ArrayList<>();
            for (String pk : partKeys) {
              sources.add(SourceObject.builder().bucket(bucket).object(pk).build());
            }

            // Perform S3 server-side object composition (composeObject)
            minioClient.composeObject(
                ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .sources(sources)
                    .build());
          } catch (Exception e) {
            log.warn(
                "S3 composeObject failed for object {}, falling back to streaming concatenation:"
                    + " {}",
                objectKey,
                e.getMessage());
            mergeUsingStreamingReupload(objectKey, partKeys, newOffset);
          }
        } else {
          log.info(
              "Detected sub-5MB non-final parts for ID {}. Using streaming concatenation fallback.",
              id);
          mergeUsingStreamingReupload(objectKey, partKeys, newOffset);
        }

        // Clean up temporary part chunk objects in S3
        for (String pk : partKeys) {
          deleteObjectQuietly(pk);
        }
      } else if (info.getLength() == 0L) {
        // Zero-byte upload: create the empty destination object in S3
        try {
          minioClient.putObject(
              PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                      new ByteArrayInputStream(new byte[0]), 0L, -1L)
                  .build());
        } catch (Exception e) {
          throw new IOException("Failed to create empty completed object for ID " + id, e);
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

  /**
   * Fallback merge method combining part objects sequentially via streaming re-upload when parts do
   * not meet AWS S3's 5 MB minimum size threshold for server-side {@code composeObject}.
   */
  private void mergeUsingStreamingReupload(
      String targetKey, List<String> partKeys, long totalLength) throws IOException {
    Enumeration<InputStream> inputStreams =
        new S3PartInputStreamEnumeration(minioClient, bucket, partKeys);

    try (InputStream combinedStream = new SequenceInputStream(inputStreams)) {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(targetKey).stream(
                  combinedStream, totalLength, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed streaming re-upload merge for key " + targetKey, e);
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

    long offset = calculateCurrentOffset(objectKey, info.getId(), partKey, info.getLength());
    info.setOffset(offset);
  }

  private long calculateCurrentOffset(
      String objectKey, UploadId id, String partKey, Long expectedLength) {
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
    long numberedPartsSize = 0L;
    for (String pk : partKeys) {
      try {
        StatObjectResponse stat =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(pk).build());
        numberedPartsSize += stat.size();
      } catch (Exception ignored) {
      }
    }
    offset += numberedPartsSize;

    if (!partKeys.contains(partKey) && objectExists(partKey)) {
      try {
        StatObjectResponse partHead =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(partKey).build());
        if (partHead != null) {
          long partSize = partHead.size();
          if (expectedLength == null || numberedPartsSize + partSize <= expectedLength) {
            offset += partSize;
          } else if (numberedPartsSize >= expectedLength) {
            // Numbered parts already cover the file; partKey is a stale orphan
            deleteObjectQuietly(partKey);
          }
        }
      } catch (ErrorResponseException ignored) {
      } catch (Exception e) {
        log.debug("Error reading head for incomplete part object {}", partKey, e);
      }
    }

    return offset;
  }

  private List<String> fetchExistingPartKeys(UploadId id) {
    String prefix = metadataPrefix + Objects.toString(id) + ".part.";
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

  /**
   * Calculates auto-calibrated optimal chunk part size based on total upload length.
   *
   * <p>AWS S3 multipart uploads enforce a strict ceiling of 10,000 parts per object. When an upload
   * length exceeds 80 GB (10,000 * 8 MB), the part size dynamically scales up (e.g. ~105 MB for 1
   * TB, ~525 MB for 5 TB) so that the entire upload is guaranteed to fit within 10,000 parts,
   * bounded by S3's 5 GB maximum part limit.
   *
   * @param totalLength The announced total upload length in bytes, or null if unknown/deferred
   * @return The calibrated optimal part size in bytes
   */
  long calcOptimalPartSize(Long totalLength) {
    long partSize = preferredPartSize;
    if (totalLength != null && totalLength > 0 && totalLength / partSize >= MAX_PARTS_PER_UPLOAD) {
      partSize = (totalLength / MAX_PARTS_PER_UPLOAD) + 1;
    }
    return Math.max(minPartSize, Math.min(partSize, DEFAULT_MAX_PART_SIZE));
  }

  /**
   * Validates that the upload has sufficient part budget remaining within S3's 10,000 parts limit.
   *
   * <p><b>Why:</b> AWS S3 enforces a strict maximum ceiling of 10,000 parts per multipart upload.
   * If an upload receives too many small chunks, it risks hitting this limit before finishing. This
   * check runs in O(1) time at the trust boundary to prevent deadlocked uploads.
   *
   * @param upload The current upload metadata
   * @param currentPartCount Number of already committed parts
   * @throws MaxAppendSizeExceededException If remaining part capacity is exhausted
   */
  void validateRemainingPartBudget(UploadInfo upload, int currentPartCount)
      throws MaxAppendSizeExceededException {
    if (currentPartCount >= MAX_PARTS_PER_UPLOAD) {
      throw new MaxAppendSizeExceededException(
          "Upload has reached the maximum allowed S3 limit of " + MAX_PARTS_PER_UPLOAD + " parts.");
    }
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
    return objectPrefix + Objects.toString(id);
  }

  private String buildMetadataKey(UploadId id) {
    return metadataPrefix + Objects.toString(id) + ".info";
  }

  private String buildIncompletePartKey(UploadId id) {
    return metadataPrefix + Objects.toString(id) + ".part";
  }

  private String buildChunkPartKey(UploadId id, int partNumber) {
    return metadataPrefix + Objects.toString(id) + ".part." + String.format("%05d", partNumber);
  }

  private String buildChecksumKey(String checksum, ChecksumAlgorithm algorithm) {
    String algorithmName = algorithm != null ? algorithm.getTusName().toLowerCase() : "unknown";
    return checksumsPrefix + algorithmName + "/" + checksum;
  }

  private String buildLockKey(UploadId id) {
    return locksPrefix + Objects.toString(id) + ".lock";
  }

  private String buildStopKey(UploadId id) {
    return locksPrefix + Objects.toString(id) + ".stop";
  }

  private static class AppendResult {
    final long totalBytesAppended;
    final List<String> allPartKeys;

    AppendResult(long totalBytesAppended, List<String> allPartKeys) {
      this.totalBytesAppended = totalBytesAppended;
      this.allPartKeys = allPartKeys;
    }
  }

  /**
   * Helper value object encapsulating an input stream prepended with prior incomplete chunk bytes,
   * the number of prepended bytes (for base offset calibration), and the remaining part keys.
   */
  private static class PreparedStream {
    final InputStream stream;
    final long prependedBytes;
    final List<String> remainingPartKeys;

    PreparedStream(InputStream stream, long prependedBytes, List<String> remainingPartKeys) {
      this.stream = stream;
      this.prependedBytes = prependedBytes;
      this.remainingPartKeys = remainingPartKeys;
    }
  }

  /**
   * Enumeration that lazily queries and opens S3 input streams for a list of part keys, enabling
   * streaming re-upload concatenation without holding all parts in memory.
   */
  private static class S3PartInputStreamEnumeration implements Enumeration<InputStream> {
    private final MinioClient minioClient;
    private final String bucket;
    private final List<String> partKeys;
    private int index = 0;

    S3PartInputStreamEnumeration(MinioClient minioClient, String bucket, List<String> partKeys) {
      this.minioClient = minioClient;
      this.bucket = bucket;
      this.partKeys = partKeys;
    }

    @Override
    public boolean hasMoreElements() {
      return index < partKeys.size();
    }

    @Override
    public InputStream nextElement() {
      if (!hasMoreElements()) {
        throw new NoSuchElementException();
      }
      String key = partKeys.get(index++);
      try {
        return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
      } catch (Exception e) {
        throw new RuntimeException("Failed to read part " + key + " for streaming merge", e);
      }
    }
  }
}
