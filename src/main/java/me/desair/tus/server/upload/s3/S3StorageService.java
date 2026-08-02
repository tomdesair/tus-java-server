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
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MinIO S3-backed implementation of {@link UploadStorageService} using the lightweight MinIO Java
 * SDK.
 *
 * <p>Key Design Architecture:
 *
 * <ul>
 *   <li><b>Server-Side Object Composition</b>: Uses S3/MinIO {@code composeObject} for scalable
 *       multi-gigabyte uploads and virtual upload concatenation with zero server memory footprint.
 *   <li><b>Incomplete Part Buffering</b>: Sub-5MB chunks (below S3's minimum part size limit) are
 *       persisted as temporary {@code .part} objects in S3 and prepended automatically on
 *       subsequent appends.
 *   <li><b>Dynamic Scaling</b>: Part sizes auto-scale up to 5GB based on total expected upload
 *       size.
 *   <li><b>Zero-Byte & Deduplication Support</b>: Handles 0-byte uploads seamlessly and supports
 *       checksum deduplication.
 * </ul>
 */
public class S3StorageService implements UploadStorageService {

  private static final Logger log = LoggerFactory.getLogger(S3StorageService.class);

  public static final String DEFAULT_OBJECT_PREFIX = "tus-uploads/";
  public static final String DEFAULT_METADATA_PREFIX = "metadata/";
  public static final String DEFAULT_CHECKSUMS_PREFIX = "checksums/";
  public static final String DEFAULT_LOCKS_PREFIX = "locks/";

  private static final long DEFAULT_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MB
  private static final long DEFAULT_PREFERRED_PART_SIZE = 50L * 1024 * 1024; // 50 MB
  private static final long DEFAULT_MAX_PART_SIZE = 5L * 1024 * 1024 * 1024L; // 5 GB

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
   * @param objectPrefix Key prefix for data objects
   * @param metadataPrefix Key prefix for metadata (.info/.part) objects
   * @param checksumsPrefix Key prefix for checksum index objects
   * @param locksPrefix Key prefix for lock lease objects
   * @param temporaryDirectory Directory path for buffering parts before S3 upload
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

    this.concatenationService =
        new S3ConcatenationService(
            this.minioClient, this.bucket, this.objectPrefix, this, this.temporaryDirectory);
  }

  /**
   * Returns the S3 object key for the completed upload data of the given upload info.
   *
   * @param uploadInfo The upload info object
   * @return The full S3 object key for the uploaded data
   */
  public String getS3ObjectKey(UploadInfo uploadInfo) {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return null;
    }
    String id = uploadInfo.getId().toString();
    if (uploadInfo.getStorageUploadId() != null && !uploadInfo.getStorageUploadId().equals(id)) {
      return uploadInfo.getStorageUploadId();
    }
    return buildObjectKey(id);
  }

  /**
   * Return the S3 object key where the uploaded bytes are stored for the given upload URI.
   *
   * @param uploadUri The HTTP request URI of the upload
   * @return The target S3 object key or null if upload not found
   */
  public String getS3ObjectKey(String uploadUri) {
    try {
      UploadId uploadId = idFactory.readUploadId(uploadUri);
      if (uploadId == null) {
        return null;
      }
      UploadInfo uploadInfo = getUploadInfo(uploadId);
      return getS3ObjectKey(uploadInfo);
    } catch (IOException e) {
      log.debug("Error retrieving upload info for URI {}", uploadUri, e);
      return null;
    }
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
    if (info != null && info.getOwnerKey() != null && !info.getOwnerKey().equals(ownerKey)) {
      return null;
    }
    return info;
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    if (id == null) {
      return null;
    }

    String metadataKey = buildMetadataKey(id.toString());
    String json;
    try (InputStream stream =
        minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(metadataKey).build())) {
      json = IOUtils.toString(stream, StandardCharsets.UTF_8);
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return null;
      }
      throw new IOException("Failed to fetch metadata object from S3 for ID " + id, e);
    } catch (Exception e) {
      throw new IOException("Failed to fetch metadata object from S3 for ID " + id, e);
    }

    UploadInfo info = UploadInfoSerializer.deserialize(json);
    if (info == null) {
      return null;
    }

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

    if (info.getId() == null) {
      info.setId(idFactory.createId());
    }
    info.setOwnerKey(ownerKey);
    info.setStorageUploadId(info.getId().toString());

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
    UploadInfo info = fetchAndValidateUpload(upload.getId());
    String id = info.getId().toString();
    String objectKey = getS3ObjectKey(info);
    String partObjectKey = buildIncompletePartKey(id);

    InputStream streamToRead = prepareStreamWithExistingIncompletePart(partObjectKey, inputStream);
    AppendResult appendResult = processPayloadChunks(info, streamToRead, id, partObjectKey);

    if (minAppendSize != null && appendResult.totalBytesAppended < minAppendSize) {
      throw new MinAppendSizeNotMetException(
          "Append payload size "
              + appendResult.totalBytesAppended
              + " is below minimum limit "
              + minAppendSize);
    }

    long newOffset = calculateCurrentOffset(objectKey, id, partObjectKey);
    info.setOffset(newOffset);

    finalizeCompletedUploadIfFinished(info, objectKey, id, appendResult, newOffset);
    update(info);
    return info;
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String metadataKey = buildMetadataKey(uploadInfo.getId().toString());
    String json = UploadInfoSerializer.serialize(uploadInfo);
    byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

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

    if (isUploadDeduplicationEnabled()
        && uploadInfo.getChecksum() != null
        && !uploadInfo.isUploadInProgress()
        && uploadInfo.getDuplicatesUploadId() == null) {
      putChecksumIndex(
          uploadInfo.getChecksum(),
          uploadInfo.getChecksumAlgorithm(),
          uploadInfo.getId().toString());
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

    if (info.getDuplicatesUploadId() != null) {
      return getUploadedBytes(info.getDuplicatesUploadId());
    }

    if (UploadType.CONCATENATED.equals(info.getUploadType()) && info.getStorageUploadId() == null) {
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
    String id = uploadInfo.getId().toString();
    String objectKey = getS3ObjectKey(uploadInfo);
    String partKey = buildIncompletePartKey(id);

    long newOffset = Math.max(0L, uploadInfo.getOffset() - byteCount);
    uploadInfo.setOffset(newOffset);
    update(uploadInfo);

    if (objectExists(objectKey)) {
      truncateFromCompletedObject(objectKey, partKey, newOffset);
      return;
    }

    truncateFromIncompletePart(partKey, byteCount);
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String id = uploadInfo.getId().toString();
    String objectKey = getS3ObjectKey(uploadInfo);
    String metadataKey = buildMetadataKey(id);
    String partKey = buildIncompletePartKey(id);

    deleteObjectQuietly(objectKey);
    deleteObjectQuietly(metadataKey);
    deleteObjectQuietly(partKey);

    // Delete all temporary part files
    deleteAllPartObjectsQuietly(id);

    if (uploadInfo.getChecksum() != null && uploadInfo.getChecksumAlgorithm() != null) {
      deleteObjectQuietly(
          buildChecksumKey(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm()));
    }
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

    UploadInfo parentInfo = getUploadInfo(new UploadId(parentIdStr));
    if (parentInfo == null || !objectExists(buildObjectKey(parentIdStr))) {
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

  // PRIVATE HELPER METHODS

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
      if ("NoSuchKey".equalsIgnoreCase(e.errorResponse().code())) {
        // Normal case: no leftover .part object
      }
    } catch (Exception ignored) {
    }
    return inputStream;
  }

  private AppendResult processPayloadChunks(
      UploadInfo info, InputStream streamToRead, String id, String partObjectKey)
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
      try (FileOutputStream fos = new FileOutputStream(tempChunkFile)) {
        int bytesRead;
        while (chunkBytesWritten < optimalPartSize
            && (bytesRead = streamToRead.read(buffer)) != -1) {
          if (maxAppendSize != null && (totalBytesAppended + bytesRead) > maxAppendSize) {
            tempChunkFile.delete();
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
      }

      if (chunkBytesWritten == 0) {
        tempChunkFile.delete();
        break;
      }

      long currentTotalOffset = info.getOffset() + totalBytesAppended;
      boolean isUploadComplete = info.getLength() != null && currentTotalOffset >= info.getLength();

      if (chunkBytesWritten >= minPartSize || (streamFinished && isUploadComplete)) {
        String chunkKey = buildChunkPartKey(id, nextPartNumber);
        uploadChunkToS3(chunkKey, tempChunkFile, chunkBytesWritten);
        allPartKeys.add(chunkKey);
        nextPartNumber++;
      } else {
        storeIncompletePartToS3(partObjectKey, tempChunkFile, chunkBytesWritten);
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
      tempChunkFile.delete();
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
      tempChunkFile.delete();
    }
  }

  private void finalizeCompletedUploadIfFinished(
      UploadInfo info, String objectKey, String id, AppendResult appendResult, long newOffset)
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
        if (partKeys.size() == 1) {
          // Single part: rename/copy single part key to objectKey or compose
          List<SourceObject> sources = new ArrayList<>();
          sources.add(SourceObject.builder().bucket(bucket).object(partKeys.get(0)).build());
          try {
            minioClient.composeObject(
                ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .sources(sources)
                    .build());
          } catch (Exception e) {
            throw new IOException("Failed to compose final single object " + objectKey, e);
          }
        } else {
          // Multiple parts: compose all part keys into final objectKey server-side
          List<SourceObject> sources = new ArrayList<>();
          for (String pk : partKeys) {
            sources.add(SourceObject.builder().bucket(bucket).object(pk).build());
          }
          try {
            minioClient.composeObject(
                ComposeObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .sources(sources)
                    .build());
          } catch (Exception e) {
            throw new IOException("Failed to compose final multipart object " + objectKey, e);
          }
        }

        // Clean up temporary part chunk objects
        for (String pk : partKeys) {
          deleteObjectQuietly(pk);
        }
      }

      if (isUploadDeduplicationEnabled()
          && info.getChecksum() != null
          && info.getDuplicatesUploadId() == null) {
        putChecksumIndex(info.getChecksum(), info.getChecksumAlgorithm(), info.getId().toString());
      }
    }
  }

  private InputStream fetchS3ByteStream(UploadId id, UploadInfo info)
      throws UploadNotFoundException {
    String objectKey = getS3ObjectKey(info);
    if (objectKey == null && id != null) {
      objectKey = buildObjectKey(id.toString());
    }
    try {
      // Step 1: Attempt to read from the completed object key in S3
      return minioClient.getObject(
          GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        // Step 2: If completed object is not found, check for an incomplete .part object from an
        // ongoing upload
        String partKey = buildIncompletePartKey(id.toString());
        try {
          return minioClient.getObject(
              GetObjectArgs.builder().bucket(bucket).object(partKey).build());
        } catch (ErrorResponseException ex) {
          if (S3Utils.parseErrorResponse(ex) == S3ErrorType.NO_SUCH_KEY) {
            if (info != null && (info.getOffset() == null || info.getOffset() == 0L)) {
              return new ByteArrayInputStream(new byte[0]);
            }
          }
        } catch (Exception ignored) {
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
        InputStream partStream =
            minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(partKey).build());
        byte[] bytes = IOUtils.toByteArray(partStream);
        int newLength = (int) (bytes.length - byteCount);
        byte[] remaining = java.util.Arrays.copyOf(bytes, newLength);

        minioClient.putObject(
            PutObjectArgs.builder().bucket(bucket).object(partKey).stream(
                    new ByteArrayInputStream(remaining), (long) remaining.length, -1L)
                .build());
      }
    } catch (ErrorResponseException ignored) {
    } catch (Exception e) {
      log.debug("Error truncating incomplete part object {}", partKey, e);
    }
  }

  private void calculateAndSetOffset(UploadInfo info) {
    String id = info.getId().toString();
    String objectKey = getS3ObjectKey(info);
    String partKey = buildIncompletePartKey(id);

    long offset = calculateCurrentOffset(objectKey, id, partKey);
    info.setOffset(offset);
  }

  private long calculateCurrentOffset(String objectKey, String id, String partKey) {
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

    // If partKey is not part of the partKeys list, check if it exists and add its size to the
    // offset
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

  private List<String> fetchExistingPartKeys(String id) {
    String prefix = metadataPrefix + id + ".part.";
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

  private void deleteAllPartObjectsQuietly(String id) {
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

  private void putChecksumIndex(String checksum, ChecksumAlgorithm algorithm, String parentId) {
    String key = buildChecksumKey(checksum, algorithm);
    try {
      byte[] parentIdBytes = parentId.getBytes(StandardCharsets.UTF_8);
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

  private String buildObjectKey(String id) {
    return objectPrefix + id;
  }

  private String buildMetadataKey(String id) {
    return metadataPrefix + id + ".info";
  }

  private String buildIncompletePartKey(String id) {
    return metadataPrefix + id + ".part";
  }

  private String buildChunkPartKey(String id, int partNumber) {
    return metadataPrefix + id + ".part." + String.format("%05d", partNumber);
  }

  private String buildChecksumKey(String checksum, ChecksumAlgorithm algorithm) {
    String algorithmName = algorithm != null ? algorithm.getTusName().toLowerCase() : "unknown";
    return checksumsPrefix + algorithmName + "/" + checksum;
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
