package me.desair.tus.server.upload.s3;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
import me.desair.tus.server.util.InterruptibleInputStream;
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
  private Duration drainTimeout = Duration.ofSeconds(55);
  private final ThreadPoolExecutor uploadExecutor;

  private Long maxUploadSize;
  private Long maxAppendSize;
  private Long minAppendSize;
  private Long minSize;
  private Long uploadExpirationPeriod;
  private boolean deduplicationEnabled = false;

  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private UploadConcatenationService concatenationService;
  private final ReadWriteLock thisObjectLock = new ReentrantReadWriteLock();
  private boolean s3ComposeObjectSupported = true;
  private boolean supportsBatchDelete = true;
  private S3ServerSideComposeHelper s3ComposeHelper;

  /**
   * Convenience constructor for local S3-compatible backends (e.g., MinIO, RustFS, Ceph) where
   * region is omitted. Defaults the region to "local".
   *
   * @param endpoint S3 endpoint URL (e.g. "http://localhost:9000")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   */
  public S3StorageService(String endpoint, String accessKey, String secretKey, String bucket) {
    this(endpoint, "local", accessKey, secretKey, bucket);
  }

  /**
   * Basic constructor accepting explicit connection parameters without exposing underlying client
   * libraries.
   *
   * @param endpoint S3 endpoint URL (e.g. "https://s3.amazonaws.com" or "http://localhost:9000")
   * @param region S3 region name (e.g. "us-east-1", "eu-central-1")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   */
  public S3StorageService(
      String endpoint, String region, String accessKey, String secretKey, String bucket) {
    this(
        endpoint,
        region,
        accessKey,
        secretKey,
        bucket,
        DEFAULT_OBJECT_PREFIX,
        DEFAULT_METADATA_PREFIX,
        DEFAULT_CHECKSUMS_PREFIX,
        DEFAULT_LOCKS_PREFIX,
        Paths.get(System.getProperty("java.io.tmpdir")));
  }

  /**
   * Full constructor accepting explicit connection parameters and prefix/buffer customization.
   *
   * <p>Delegates to the internal package-private constructor accepting {@link MinioClient}.
   *
   * @param endpoint S3 endpoint URL (e.g. "https://s3.amazonaws.com" or "http://localhost:9000")
   * @param region S3 region name (e.g. "us-east-1", "eu-central-1")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   * @param objectPrefix Key prefix for final completed file objects
   * @param metadataPrefix Key prefix for metadata (.info JSON and .part buffer) objects
   * @param checksumsPrefix Key prefix for checksum deduplication index objects
   * @param locksPrefix Key prefix for distributed lock lease objects
   * @param temporaryDirectory Local directory path for staging chunks before S3 upload
   */
  public S3StorageService(
      String endpoint,
      String region,
      String accessKey,
      String secretKey,
      String bucket,
      String objectPrefix,
      String metadataPrefix,
      String checksumsPrefix,
      String locksPrefix,
      Path temporaryDirectory) {
    this(
        buildMinioClient(endpoint, region, accessKey, secretKey),
        bucket,
        objectPrefix,
        metadataPrefix,
        checksumsPrefix,
        locksPrefix,
        temporaryDirectory,
        new S3ServerSideComposeHelper(endpoint, region, accessKey, secretKey));
  }

  /**
   * Internal package-private constructor accepting {@link MinioClient} where all parameter
   * configuration and initialization logic is concentrated.
   */
  S3StorageService(
      MinioClient minioClient,
      String bucket,
      String objectPrefix,
      String metadataPrefix,
      String checksumsPrefix,
      String locksPrefix,
      Path temporaryDirectory,
      S3ServerSideComposeHelper s3ComposeHelper) {
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

    this.s3ComposeHelper =
        s3ComposeHelper != null ? s3ComposeHelper : new S3ServerSideComposeHelper(this.minioClient);
    this.concatenationService =
        new S3ConcatenationService(
            this.minioClient,
            this.bucket,
            this.objectPrefix,
            this,
            this.temporaryDirectory,
            DEFAULT_MIN_PART_SIZE,
            this.s3ComposeHelper);
  }

  private static MinioClient buildMinioClient(
      String endpoint, String region, String accessKey, String secretKey) {
    String effectiveRegion = (region != null && !region.isEmpty()) ? region : "local";
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .region(effectiveRegion)
        .build();
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
    if (info.getUploadPartKeys() == null) {
      info.setUploadPartKeys(new ArrayList<>());
    }
    if (info.getOffset() == null) {
      info.setOffset(0L);
    }

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
    InterruptibleInputStream interruptibleStream = Utils.toInterruptibleStream(inputStream);

    // Step 2: If the last part is sub-5MB, download & prepend its bytes to the incoming stream.
    // The previous tail part is intentionally kept in S3 as a staleTailKey and is ONLY deleted
    // after the new manifest is successfully committed to S3. This ensures zero data loss if
    // this request is paused or interrupted immediately.
    PreparedStream preparedStream =
        prepareStreamWithExistingIncompletePart(info, interruptibleStream);

    // Validate that the upload has not exceeded S3's 10,000 multipart parts ceiling
    validateRemainingPartBudget(info, preparedStream.remainingPartKeys.size());

    // Clean up any unmanifested orphan part objects left in S3 from earlier aborted attempts.
    // Pruning unmanifested parts under the upload lock prevents listing delays and ensures
    // that only parts explicitly tracked in the manifest are considered valid.
    pruneOrphanParts(info.getId(), preparedStream.remainingPartKeys, preparedStream.staleTailKeys);

    // Step 3: Process payload stream in optimal chunk parts and upload to S3.
    AppendResult appendResult = processPayloadChunks(info, preparedStream, info.getId());

    // Step 4: Persist any newly confirmed chunk parts and update the byte offset.
    // Drained chunks and trailing parts are committed to the manifest before rethrowing any
    // stream interruption exception, ensuring maximum byte persistence.
    boolean hasNewParts =
        appendResult.allPartKeys.size() > preparedStream.remainingPartKeys.size()
            || appendResult.confirmedBytesAppended > 0;

    if (hasNewParts) {
      long currentOffsetBeforeStream =
          Math.max(
              info.getOffset() != null ? info.getOffset() : 0L,
              preparedStream.existingPartsTotalSize + preparedStream.prependedBytes);
      long baseOffset = currentOffsetBeforeStream - preparedStream.prependedBytes;
      long newOffset = baseOffset + appendResult.confirmedBytesAppended;
      info.setOffset(newOffset);
      upload.setOffset(newOffset);
      info.setUploadPartKeys(new ArrayList<>(appendResult.allPartKeys));
      update(info);

      // Safe transactional cleanup: Delete old tail parts only AFTER the new manifest is saved.
      // If the upload was interrupted earlier, staleTailKeys were untouched, preserving bytes.
      for (String staleKey : preparedStream.staleTailKeys) {
        deleteObjectQuietly(staleKey);
      }
    }

    // Rethrow any stream reading or size limit exceptions after metadata has been safely saved
    if (appendResult.readException != null) {
      throw appendResult.readException;
    }
    if (appendResult.maxAppendSizeException != null) {
      throw appendResult.maxAppendSizeException;
    }

    // Step 5: Validate minimum append size constraints if configured.
    // Subtract prependedBytes so minAppendSize accurately measures payload transferred in THIS
    // request. Per RUFH §4.1.4: "This limit does not apply to upload creation requests with no
    // content, or to requests completing the upload by including the Upload-Complete: ?1 header
    // field."
    long currentOffset = info.getOffset() != null ? info.getOffset() : 0L;
    boolean isCompletingOrEmpty =
        !info.isUploadInProgress()
            || (info.getLength() != null && currentOffset >= info.getLength());
    long requestPayloadAppended =
        Math.max(0L, appendResult.confirmedBytesAppended - preparedStream.prependedBytes);
    if (minAppendSize != null && !isCompletingOrEmpty && requestPayloadAppended < minAppendSize) {
      throw new MinAppendSizeNotMetException(
          "Append payload size "
              + requestPayloadAppended
              + " is below minimum limit "
              + minAppendSize);
    }

    // Step 6: If all expected bytes are uploaded, compose all part chunks into final S3 object
    finalizeCompletedUploadIfFinished(info, objectKey, info.getId(), currentOffset);
    update(info);
    return info;
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
    long newOffset = Math.max(0L, uploadInfo.getOffset() - byteCount);
    uploadInfo.setOffset(newOffset);

    // If final completed object exists in S3, truncate it
    if (objectExists(objectKey)) {
      truncateCompletedObject(uploadInfo, objectKey, newOffset);
      update(uploadInfo);
      return;
    }

    // If upload has manifest parts in uploadPartKeys, truncate backwards from parts
    if (uploadInfo.getUploadPartKeys() != null && !uploadInfo.getUploadPartKeys().isEmpty()) {
      truncateManifestParts(uploadInfo, byteCount);
      update(uploadInfo);
      return;
    }

    update(uploadInfo);
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String objectKey = getS3ObjectKey(uploadInfo);
    String metadataKey = buildMetadataKey(uploadInfo.getId());

    // Delete final object and metadata object from S3
    deleteObjectQuietly(objectKey);
    deleteObjectQuietly(metadataKey);

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

  boolean isS3ComposeObjectSupported() {
    thisObjectLock.readLock().lock();
    try {
      return s3ComposeObjectSupported;
    } finally {
      thisObjectLock.readLock().unlock();
    }
  }

  void setS3ComposeObjectSupported(boolean s3ComposeObjectSupported) {
    thisObjectLock.writeLock().lock();
    try {
      this.s3ComposeObjectSupported = s3ComposeObjectSupported;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
  }

  void setS3ServerSideComposeHelper(S3ServerSideComposeHelper s3ComposeHelper) {
    this.s3ComposeHelper = s3ComposeHelper;
  }

  S3ServerSideComposeHelper getS3ServerSideComposeHelper() {
    return s3ComposeHelper;
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

  @Override
  public void setDrainTimeout(Duration drainTimeout) {
    if (drainTimeout != null) {
      this.drainTimeout = drainTimeout;
    }
  }

  @Override
  public Duration getDrainTimeout() {
    return drainTimeout;
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

  boolean isSupportsBatchDelete() {
    thisObjectLock.readLock().lock();
    try {
      return supportsBatchDelete;
    } finally {
      thisObjectLock.readLock().unlock();
    }
  }

  void setSupportsBatchDelete(boolean supportsBatchDelete) {
    thisObjectLock.writeLock().lock();
    try {
      this.supportsBatchDelete = supportsBatchDelete;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
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
   * Prepares the incoming input stream by rolling back any prior sub-5MB incomplete tail part into
   * the stream buffer so it can continue growing.
   *
   * <p>The previous tail part is intentionally kept in S3 as a {@code staleTailKey} and is ONLY
   * deleted after the new manifest is successfully committed to S3. This ensures zero data loss if
   * this request is paused or interrupted immediately.
   *
   * @param info The upload metadata
   * @param interruptibleStream The incoming request interruptible input stream
   * @return {@link PreparedStream} containing the combined stream, prepended bytes, remaining
   *     parts, and stale tail keys
   * @throws IOException If reading or downloading from S3 fails
   */
  private PreparedStream prepareStreamWithExistingIncompletePart(
      UploadInfo info, InterruptibleInputStream interruptibleStream) throws IOException {
    long prependedBytes = 0L;
    InputStream combinedStream = interruptibleStream;
    List<String> staleTailKeys = new ArrayList<>();

    // 1. Fetch current committed part keys from manifest
    List<String> existingPartKeys =
        info.getUploadPartKeys() != null
            ? new ArrayList<>(info.getUploadPartKeys())
            : new ArrayList<>();

    long existingPartsTotalSize = calculateTotalPartsSize(existingPartKeys);

    // 2. Roll back any existing sub-5MB numbered parts into the stream buffer so they can grow
    if (!existingPartKeys.isEmpty()) {
      String lastPartKey = existingPartKeys.get(existingPartKeys.size() - 1);
      try {
        StatObjectResponse stat =
            minioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(lastPartKey).build());
        if (stat.size() < minPartSize) {
          log.info(
              "Rolling back sub-5MB part {} (size: {}) into stream buffer",
              lastPartKey,
              stat.size());
          try (InputStream lastPartStream =
              minioClient.getObject(
                  GetObjectArgs.builder().bucket(bucket).object(lastPartKey).build())) {
            byte[] lastPartBytes = IOUtils.toByteArray(lastPartStream);
            prependedBytes += lastPartBytes.length;
            combinedStream =
                new SequenceInputStream(new ByteArrayInputStream(lastPartBytes), combinedStream);
            staleTailKeys.add(lastPartKey);
            existingPartsTotalSize -= lastPartBytes.length;
            existingPartKeys.remove(existingPartKeys.size() - 1);
          }
        }
      } catch (Exception e) {
        log.debug("Error inspecting last part key {}: {}", lastPartKey, e.getMessage());
      }
    }

    return new PreparedStream(
        combinedStream,
        interruptibleStream,
        prependedBytes,
        existingPartsTotalSize,
        existingPartKeys,
        staleTailKeys);
  }

  private long calculateTotalPartsSize(List<String> partKeys) {
    long totalSize = 0L;
    for (String key : partKeys) {
      try {
        StatObjectResponse stat =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
        if (stat != null) {
          totalSize += stat.size();
        }
      } catch (Exception ignored) {
      }
    }
    return totalSize;
  }

  /**
   * Prunes unmanifested orphaned part objects left in S3 from earlier aborted parallel attempts.
   *
   * @param id The upload identifier
   * @param manifestedParts Parts actively tracked in the manifest
   * @param staleTailKeys Stale tail parts pending replacement in this request
   */
  private void pruneOrphanParts(
      UploadId id, List<String> manifestedParts, List<String> staleTailKeys) {
    List<String> s3Parts = fetchExistingPartKeys(id);
    for (String s3Part : s3Parts) {
      if (!manifestedParts.contains(s3Part) && !staleTailKeys.contains(s3Part)) {
        log.debug("Pruning unmanifested orphan S3 part {}", s3Part);
        deleteObjectQuietly(s3Part);
      }
    }
  }

  /**
   * Reads bytes from the incoming stream into temporary local files of optimal part size (default
   * 8MB). Parts &ge; 5MB are uploaded asynchronously to S3 via {@link AsyncChunkUploader},
   * overlapping client stream reading with cloud upload. Any trailing chunk under 5MB is uploaded
   * directly to S3 and tracked in the manifest.
   */
  private AppendResult processPayloadChunks(
      UploadInfo info, PreparedStream preparedStream, UploadId id) {

    List<String> allPartKeys = new ArrayList<>(preparedStream.remainingPartKeys);
    int nextPartNumber = allPartKeys.size() + 1;

    long optimalPartSize = calcOptimalPartSize(info.getLength());
    byte[] buffer = new byte[8192];
    long totalBytesAppended = 0;
    long confirmedBytesAppended = 0;
    long currentOffsetBeforeStream =
        Math.max(
            info.getOffset() != null ? info.getOffset() : 0L,
            preparedStream.existingPartsTotalSize + preparedStream.prependedBytes);
    long baseOffset = currentOffsetBeforeStream - preparedStream.prependedBytes;

    boolean streamFinished = false;
    MaxAppendSizeExceededException maxAppendSizeException = null;
    IOException readException = null;

    List<String> plannedPartKeys = new ArrayList<>();
    List<Long> plannedChunkSizes = new ArrayList<>();

    try (AsyncChunkUploader uploader =
        new AsyncChunkUploader(uploadExecutor, drainTimeout.toMillis(), preparedStream)) {
      while (!streamFinished) {
        File tempChunkFile = null;
        long chunkBytesWritten = 0;
        boolean handedOff = false;
        try {
          tempChunkFile =
              Files.createTempFile(temporaryDirectory, "tus-s3-chunk-", ".tmp").toFile();

          try (FileOutputStream fos = new FileOutputStream(tempChunkFile)) {
            int bytesRead;
            while (chunkBytesWritten < optimalPartSize
                && (bytesRead = preparedStream.read(buffer)) != -1) {
              long requestBytesSoFar =
                  (totalBytesAppended + bytesRead) - preparedStream.prependedBytes;
              if (maxAppendSize != null && requestBytesSoFar > maxAppendSize) {
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

          // Validate remaining part capacity before allocating next S3 part number
          if (allPartKeys.size() + plannedPartKeys.size() >= MAX_PARTS_PER_UPLOAD
              || nextPartNumber > MAX_PARTS_PER_UPLOAD) {
            maxAppendSizeException =
                new MaxAppendSizeExceededException(
                    "Upload has reached the maximum allowed S3 limit of "
                        + MAX_PARTS_PER_UPLOAD
                        + " parts.");
            if (chunkBytesWritten > 0) {
              int confirmedCount = uploader.drainAndComplete();
              int confirmed = Math.min(confirmedCount, plannedPartKeys.size());
              for (int i = 0; i < confirmed; i++) {
                allPartKeys.add(plannedPartKeys.get(i));
                confirmedBytesAppended += plannedChunkSizes.get(i);
              }
              plannedPartKeys.clear();
              plannedChunkSizes.clear();

              String chunkKey = buildChunkPartKey(id, nextPartNumber++);
              uploadChunkToS3(chunkKey, tempChunkFile, chunkBytesWritten);
              allPartKeys.add(chunkKey);
              confirmedBytesAppended += chunkBytesWritten;
              handedOff = true;
            }
            streamFinished = true;
            break;
          }

          long currentTotalOffset = baseOffset + totalBytesAppended;
          boolean isUploadComplete =
              readException == null
                  && maxAppendSizeException == null
                  && info.getLength() != null
                  && currentTotalOffset >= info.getLength();

          // AWS S3 / MinIO Rule: Parts must be >= 5 MB unless it's the final part completing the
          // upload or an incomplete sub-5MB tail chunk
          if (chunkBytesWritten >= minPartSize) {
            // Full part chunk (>= 5 MB): Submit to AsyncChunkUploader pipeline for background
            // upload
            String chunkKey = buildChunkPartKey(id, nextPartNumber++);
            plannedPartKeys.add(chunkKey);
            plannedChunkSizes.add(chunkBytesWritten);
            File fileToUpload = tempChunkFile;
            long bytesToUpload = chunkBytesWritten;
            uploader.submitChunk(
                tempChunkFile,
                bytesToUpload,
                chunkKey,
                () -> uploadChunkToS3(chunkKey, fileToUpload, bytesToUpload));
            handedOff = true;

          } else {
            // Sub-5MB chunk (e.g. final completing chunk, or partial chunk when
            // paused/interrupted):
            // First drain all preceding parts in the pipeline
            int confirmedCount = uploader.drainAndComplete();
            int confirmed = Math.min(confirmedCount, plannedPartKeys.size());
            for (int i = 0; i < confirmed; i++) {
              allPartKeys.add(plannedPartKeys.get(i));
              confirmedBytesAppended += plannedChunkSizes.get(i);
            }
            plannedPartKeys.clear();
            plannedChunkSizes.clear();

            String chunkKey = buildChunkPartKey(id, nextPartNumber++);
            uploadChunkToS3(chunkKey, tempChunkFile, chunkBytesWritten);
            allPartKeys.add(chunkKey);
            confirmedBytesAppended += chunkBytesWritten;
            handedOff = true;
          }

        } catch (Exception e) {
          if (e instanceof IOException && readException == null) {
            readException = (IOException) e;
          }
          streamFinished = true;
        } finally {
          if (!handedOff && tempChunkFile != null) {
            FileUtils.deleteQuietly(tempChunkFile);
          }
        }
      }

      // Drain any remaining in-flight chunks in the pipeline with configured drain timeout
      int confirmedCount = 0;
      try {
        confirmedCount = uploader.drainAndComplete();
      } catch (IOException e) {
        if (readException == null) {
          readException = e;
        }
      } finally {
        int confirmed =
            Math.min(
                Math.max(confirmedCount, uploader.getConfirmedCount()), plannedPartKeys.size());
        for (int i = 0; i < confirmed; i++) {
          allPartKeys.add(plannedPartKeys.get(i));
          confirmedBytesAppended += plannedChunkSizes.get(i);
        }
      }
    } catch (Exception e) {
      if (e instanceof IOException && readException == null) {
        readException = (IOException) e;
      }
    }

    return new AppendResult(
        confirmedBytesAppended, allPartKeys, readException, maxAppendSizeException);
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
      UploadInfo info, String objectKey, UploadId id, long newOffset) throws IOException {

    if (info.getLength() != null && newOffset >= info.getLength()) {
      List<String> partKeys =
          info.getUploadPartKeys() != null
              ? new ArrayList<>(info.getUploadPartKeys())
              : new ArrayList<>();

      long sumPartSizes = calculateTotalPartsSize(partKeys);

      // Guard: Enforce strict byte length equality before final object composition.
      // This invariant prevents corrupted uploads where duplicate parts or missed chunk offsets
      // would cause the final object to exceed or fall short of the declared length.
      if (info.getLength() > 0 && sumPartSizes > 0 && sumPartSizes != info.getLength()) {
        throw new IOException(
            "Upload finalization aborted: sum of part sizes ("
                + sumPartSizes
                + ") does not match expected length ("
                + info.getLength()
                + ")");
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

        if (canUseServerSideCompose && isS3ComposeObjectSupported()) {
          try {
            // Perform S3 server-side object composition via native S3 multipart copy
            s3ComposeHelper.compose(bucket, objectKey, partKeys);
          } catch (Exception e) {
            // MinIO Java SDK's composeObject implementation delegates to UploadPartCopy with an
            // EMPTY_BODY, which automatically attaches Content-MD5 and Content-Type headers.
            // AWS S3 strictly forbids Content-MD5 on UploadPartCopy and rejects it with 400
            // InvalidArgument ("The specified header is not valid in this context").
            // When server-side compose fails on this S3 endpoint, disable it dynamically
            // to avoid redundant failing S3 API roundtrips on subsequent uploads, log at INFO,
            // and seamlessly merge chunks via streaming part composition.
            setS3ComposeObjectSupported(false);
            log.info(
                "S3 server-side compose failed for object {}, falling back to streaming part"
                    + " composition: {}",
                objectKey,
                e.getMessage());
            mergeUsingStreamingReupload(objectKey, partKeys, newOffset);
          }
        } else {
          if (!canUseServerSideCompose) {
            log.info(
                "Detected sub-5MB non-final parts for ID {}. Using streaming part composition"
                    + " fallback.",
                id);
          }
          mergeUsingStreamingReupload(objectKey, partKeys, newOffset);
        }

        // Clean up temporary part chunk objects in S3 using multi-delete with fallback
        deleteS3ObjectsQuietly(partKeys);
        info.setUploadPartKeys(null);

      } else if (info.getLength() == 0L) {
        // Zero-byte upload: create the empty destination object in S3
        try {
          minioClient.putObject(
              PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                      new ByteArrayInputStream(new byte[0]), 0L, -1L)
                  .build());
          info.setUploadPartKeys(null);
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
        // Step 2: Fallback to reading from manifest part objects if upload is in-progress
        List<String> partKeys =
            info != null && info.getUploadPartKeys() != null
                ? info.getUploadPartKeys()
                : Collections.emptyList();
        if (!partKeys.isEmpty()) {
          try {
            Enumeration<InputStream> inputStreams =
                new S3PartInputStreamEnumeration(minioClient, bucket, partKeys);
            return new SequenceInputStream(inputStreams);
          } catch (Exception ex) {
            log.debug("Failed to stream manifest parts for upload ID {}", id, ex);
          }
        }

        if (info != null && (info.getOffset() == null || info.getOffset() == 0L)) {
          return new ByteArrayInputStream(new byte[0]);
        }
      }
      throw new UploadNotFoundException("Uploaded bytes object not found for ID " + id);
    } catch (Exception e) {
      throw new UploadNotFoundException("Uploaded bytes object not found for ID " + id);
    }
  }

  /**
   * Truncates bytes backwards across committed manifest parts in reverse chronological order.
   *
   * <p>AWS S3 objects are immutable, so truncation cannot truncate in-place without replacing the
   * affected object. If a part's total size is less than or equal to the bytes to remove, the
   * entire part object is deleted from S3 and removed from the manifest. If a part's size exceeds
   * the bytes to remove, {@link #truncateSinglePart(String, long, long)} rewrites the remaining
   * prefix bytes to S3.
   *
   * @param uploadInfo The upload metadata containing the list of part keys
   * @param bytesToRemove The total number of bytes to strip from the tail
   */
  private void truncateManifestParts(UploadInfo uploadInfo, long bytesToRemove) {
    List<String> partKeys = new ArrayList<>(uploadInfo.getUploadPartKeys());
    long bytesToRemoveRemaining = bytesToRemove;

    while (!partKeys.isEmpty() && bytesToRemoveRemaining > 0) {
      String lastKey = partKeys.get(partKeys.size() - 1);
      try {
        StatObjectResponse stat =
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(lastKey).build());
        long partSize = stat.size();

        if (bytesToRemoveRemaining >= partSize) {
          // The entire part is subsumed by the truncation; delete it and adjust remaining budget
          deleteObjectQuietly(lastKey);
          partKeys.remove(partKeys.size() - 1);
          bytesToRemoveRemaining -= partSize;
        } else {
          // Only a fraction of this part is being removed; rewrite the remaining prefix
          truncateSinglePart(lastKey, partSize, bytesToRemoveRemaining);
          bytesToRemoveRemaining = 0;
        }
      } catch (Exception e) {
        log.debug("Error truncating manifest part {}", lastKey, e);
        break;
      }
    }
    uploadInfo.setUploadPartKeys(partKeys);
  }

  /**
   * Partially truncates an individual S3 part object by downloading its content and overwriting it
   * in S3 with only the remaining byte prefix.
   *
   * @param partKey S3 object key of the part to partially truncate
   * @param partSize Current total size of the part object
   * @param bytesToRemove Number of tail bytes to remove from this specific part
   * @throws IOException If reading or re-uploading the truncated part fails
   */
  private void truncateSinglePart(String partKey, long partSize, long bytesToRemove)
      throws IOException {
    try (InputStream partStream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(partKey).build())) {
      byte[] bytes = IOUtils.toByteArray(partStream);
      int newLength = (int) (partSize - bytesToRemove);
      byte[] remaining = Arrays.copyOf(bytes, newLength);
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(partKey).stream(
                  new ByteArrayInputStream(remaining), (long) remaining.length, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed to partially truncate S3 part object " + partKey, e);
    }
  }

  /**
   * Truncates a final completed S3 object by converting the remaining byte prefix into a manifest
   * part and deleting the completed object.
   *
   * @param uploadInfo The upload metadata to update with the new manifest part
   * @param objectKey S3 object key of the completed object
   * @param newOffset The truncated target byte length
   * @throws IOException If downloading or rewriting the object fails
   */
  private void truncateCompletedObject(UploadInfo uploadInfo, String objectKey, long newOffset)
      throws IOException {
    if (newOffset > 0) {
      try (InputStream objStream =
          minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
        byte[] remainingBytes = new byte[(int) newOffset];
        IOUtils.readFully(objStream, remainingBytes);
        String chunkKey = buildChunkPartKey(uploadInfo.getId(), 1);
        minioClient.putObject(
            PutObjectArgs.builder().bucket(bucket).object(chunkKey).stream(
                    new ByteArrayInputStream(remainingBytes), (long) remainingBytes.length, -1L)
                .build());
        uploadInfo.setUploadPartKeys(new ArrayList<>(Collections.singletonList(chunkKey)));
      } catch (Exception e) {
        throw new IOException("Failed to truncate completed object key " + objectKey, e);
      }
    } else {
      uploadInfo.setUploadPartKeys(new ArrayList<>());
    }
    deleteObjectQuietly(objectKey);
  }

  private void calculateAndSetOffset(UploadInfo info) {
    if (info == null || info.getId() == null) {
      return;
    }
    String objectKey = getS3ObjectKey(info);
    if (objectExists(objectKey)) {
      try {
        StatObjectResponse head =
            minioClient.statObject(
                StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
        if (head != null) {
          info.setOffset(head.size());
          return;
        }
      } catch (Exception ignored) {
      }
    }

    List<String> partKeys =
        info.getUploadPartKeys() != null ? info.getUploadPartKeys() : Collections.emptyList();
    long offset = calculateTotalPartsSize(partKeys);
    info.setOffset(offset);
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
    Collections.sort(partKeys);
    return partKeys;
  }

  private void deleteAllPartObjectsQuietly(UploadId id) {
    List<String> partKeys = fetchExistingPartKeys(id);
    deleteS3ObjectsQuietly(partKeys);
  }

  /**
   * Deletes multiple S3 objects efficiently using batch Multi-Object Delete (POST /?delete), with
   * automatic fallback to individual single-object DELETE requests if batch delete is unsupported
   * or rejected by bucket IAM permissions.
   *
   * <p>If batch delete fails once, it is permanently disabled for this storage service instance to
   * avoid unnecessary failing S3 API round-trips.
   *
   * @param keys The S3 object keys to delete
   */
  void deleteS3ObjectsQuietly(Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }

    if (!isSupportsBatchDelete()) {
      deleteObjectsIndividually(keys);
      return;
    }

    List<DeleteRequest.Object> objects = toDeleteRequestObjects(keys);
    if (objects.isEmpty()) {
      return;
    }

    boolean batchFailed = false;
    try {
      Iterable<Result<DeleteResult.Error>> results =
          minioClient.removeObjects(
              RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build());
      if (results != null) {
        for (Result<DeleteResult.Error> res : results) {
          if (res != null) {
            DeleteResult.Error error = res.get();
            if (error != null) {
              log.debug(
                  "Batch delete reported error for key {}: {}; switching to individual delete",
                  error.objectName(),
                  error.message());
              batchFailed = true;
              break;
            }
          }
        }
      }
    } catch (Exception e) {
      log.info(
          "Batch multi-delete failed; permanently switching to individual delete: {}",
          e.getMessage());
      batchFailed = true;
    }

    if (batchFailed) {
      setSupportsBatchDelete(false);
      deleteObjectsIndividually(keys);
    }
  }

  private List<DeleteRequest.Object> toDeleteRequestObjects(Collection<String> keys) {
    List<DeleteRequest.Object> objects = new ArrayList<>();
    for (String key : keys) {
      if (key != null) {
        objects.add(new DeleteRequest.Object(key));
      }
    }
    return objects;
  }

  private void deleteObjectsIndividually(Collection<String> keys) {
    for (String key : keys) {
      deleteObjectQuietly(key);
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

  private String buildChunkPartKey(UploadId id, int partNumber) {
    return metadataPrefix
        + Objects.toString(id)
        + ".part."
        + String.format("%05d-%s", partNumber, UUID.randomUUID().toString().substring(0, 8));
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
    final long confirmedBytesAppended;
    final List<String> allPartKeys;
    final IOException readException;
    final MaxAppendSizeExceededException maxAppendSizeException;

    AppendResult(
        long confirmedBytesAppended,
        List<String> allPartKeys,
        IOException readException,
        MaxAppendSizeExceededException maxAppendSizeException) {
      this.confirmedBytesAppended = confirmedBytesAppended;
      this.allPartKeys = allPartKeys;
      this.readException = readException;
      this.maxAppendSizeException = maxAppendSizeException;
    }
  }

  /**
   * Helper stream wrapper encapsulating an input stream prepended with prior incomplete chunk
   * bytes, delegating interruption state to the original incoming stream.
   */
  private static class PreparedStream extends InterruptibleInputStream {
    private final InterruptibleInputStream originalStream;
    final long prependedBytes;
    final long existingPartsTotalSize;
    final List<String> remainingPartKeys;
    final List<String> staleTailKeys;

    PreparedStream(
        InputStream combinedStream,
        InterruptibleInputStream originalStream,
        long prependedBytes,
        long existingPartsTotalSize,
        List<String> remainingPartKeys,
        List<String> staleTailKeys) {
      super(combinedStream);
      this.originalStream = originalStream;
      this.prependedBytes = prependedBytes;
      this.existingPartsTotalSize = existingPartsTotalSize;
      this.remainingPartKeys = remainingPartKeys;
      this.staleTailKeys = staleTailKeys;
    }

    @Override
    public boolean isInterrupted() {
      return super.isInterrupted() || (originalStream != null && originalStream.isInterrupted());
    }

    @Override
    public void interrupt() {
      super.interrupt();
      if (originalStream != null) {
        originalStream.interrupt();
      }
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
