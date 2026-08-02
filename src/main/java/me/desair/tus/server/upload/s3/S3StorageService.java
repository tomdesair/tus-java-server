package me.desair.tus.server.upload.s3;

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
import java.util.Collections;
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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.NoSuchUploadException;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * S3-compatible implementation of {@link UploadStorageService}.
 *
 * <p>Key Design Architecture:
 *
 * <ul>
 *   <li><b>Always Multipart Upload Strategy</b>: Employs S3 multipart uploads matching {@code tusd}
 *       architecture for scalable multi-gigabyte uploads.
 *   <li><b>Incomplete Part Buffering</b>: Sub-5MB chunks (below S3's minimum part size limit) are
 *       persisted as temporary {@code .part} objects in S3 and prepended automatically on
 *       subsequent appends.
 *   <li><b>Dynamic Dynamic Scaling</b>: Part sizes auto-scale up to 5GB based on total expected
 *       upload size.
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
  private static final int MAX_MULTIPART_PARTS = 10_000;

  private final S3Client s3Client;
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
   * @param s3Client Pre-configured S3Client
   * @param bucket S3 bucket name
   */
  public S3StorageService(S3Client s3Client, String bucket) {
    this(
        s3Client,
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
   * @param s3Client Pre-configured S3Client
   * @param bucket S3 bucket name
   * @param objectPrefix Key prefix for data objects
   * @param metadataPrefix Key prefix for metadata (.info/.part) objects
   * @param checksumsPrefix Key prefix for checksum index objects
   * @param locksPrefix Key prefix for lock lease objects
   * @param temporaryDirectory Directory path for buffering parts before S3 upload
   */
  public S3StorageService(
      S3Client s3Client,
      String bucket,
      String objectPrefix,
      String metadataPrefix,
      String checksumsPrefix,
      String locksPrefix,
      Path temporaryDirectory) {
    this.s3Client = Objects.requireNonNull(s3Client, "S3Client must not be null");
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
            this.s3Client, this.bucket, this.objectPrefix, this, this.temporaryDirectory);
  }

  /**
   * Returns the S3 object key for the completed upload data of the given upload.
   *
   * @param uploadInfo The upload info object
   * @return The full S3 object key for the uploaded data
   */
  public String getS3ObjectKey(UploadInfo uploadInfo) {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return null;
    }
    return buildObjectKey(uploadInfo.getId().toString());
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
    try (ResponseInputStream<GetObjectResponse> stream =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(metadataKey).build())) {
      json = IOUtils.toString(stream, StandardCharsets.UTF_8);
    } catch (NoSuchKeyException e) {
      return null;
    } catch (Exception e) {
      throw new IOException("Failed to fetch metadata object from S3 for ID " + id, e);
    }

    UploadInfo info = UploadInfoSerializer.deserialize(json);
    if (info == null) {
      return null;
    }

    info.setId(id);
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

    String objectKey = buildObjectKey(info.getId().toString());
    CreateMultipartUploadResponse response =
        createS3MultipartUpload(objectKey, info.getFileMimeType());
    info.setStorageUploadId(response.uploadId());

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
    // 1. High-level verification & setup
    UploadInfo info = fetchAndValidateUpload(upload.getId());
    String objectKey = buildObjectKey(info.getId().toString());
    String partObjectKey = buildIncompletePartKey(info.getId().toString());

    // 2. Prepare incoming byte stream by prepending leftover sub-5MB .part buffer if present
    InputStream streamToRead = prepareStreamWithExistingIncompletePart(partObjectKey, inputStream);

    // 3. Ensure active S3 multipart upload ID exists
    String multipartUploadId = ensureMultipartUploadId(info, objectKey);

    // 4. Read incoming stream and upload complete 5MB+ parts to S3 (buffering sub-5MB leftovers to
    // .part)
    List<CompletedPart> existingParts = fetchCompletedParts(objectKey, multipartUploadId);
    AppendResult appendResult =
        processPayloadChunks(
            info, streamToRead, objectKey, multipartUploadId, partObjectKey, existingParts);

    // 5. Enforce minimum append payload size rule
    if (minAppendSize != null && appendResult.totalBytesAppended < minAppendSize) {
      throw new MinAppendSizeNotMetException(
          "Append payload size "
              + appendResult.totalBytesAppended
              + " is below minimum limit "
              + minAppendSize);
    }

    // 6. Recalculate authoritative offset and finalize complete uploads
    long newOffset = calculateCurrentOffset(objectKey, multipartUploadId, partObjectKey);
    info.setOffset(newOffset);

    finalizeCompletedUploadIfFinished(
        info, objectKey, multipartUploadId, appendResult.allParts, newOffset);
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

    s3Client.putObject(
        PutObjectRequest.builder().bucket(bucket).key(metadataKey).build(),
        RequestBody.fromString(json, StandardCharsets.UTF_8));

    // Index completed parent uploads for checksum deduplication
    if (isUploadDeduplicationEnabled()
        && uploadInfo.getChecksum() != null
        && uploadInfo.getChecksumAlgorithm() != null
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

    // Direct parent upload resolution for duplicate uploads
    if (info.getDuplicatesUploadId() != null) {
      return getUploadedBytes(info.getDuplicatesUploadId());
    }

    // Trigger virtual concatenation merge if needed
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
      ListObjectsV2Response response =
          s3Client.listObjectsV2(
              ListObjectsV2Request.builder().bucket(bucket).prefix(metadataPrefix).build());

      for (S3Object obj : response.contents()) {
        if (obj.key().endsWith(".info")) {
          String idStr =
              obj.key().substring(metadataPrefix.length(), obj.key().length() - ".info".length());
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
    String objectKey = buildObjectKey(id);
    String partKey = buildIncompletePartKey(id);

    long newOffset = Math.max(0L, uploadInfo.getOffset() - byteCount);
    uploadInfo.setOffset(newOffset);
    update(uploadInfo);

    // Strategy 1: Truncate completed S3 object if present
    if (objectExists(objectKey)) {
      truncateFromCompletedObject(objectKey, partKey, newOffset);
      return;
    }

    // Strategy 2: Truncate from incomplete .part object if present
    truncateFromIncompletePart(partKey, byteCount);
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    String id = uploadInfo.getId().toString();
    String objectKey = buildObjectKey(id);
    String metadataKey = buildMetadataKey(id);
    String partKey = buildIncompletePartKey(id);

    if (uploadInfo.getStorageUploadId() != null) {
      abortMultipartUploadQuietly(objectKey, uploadInfo.getStorageUploadId());
    }

    deleteObjectQuietly(objectKey);
    deleteObjectQuietly(metadataKey);
    deleteObjectQuietly(partKey);

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
    try (ResponseInputStream<GetObjectResponse> stream =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(checksumKey).build())) {
      parentIdStr = IOUtils.toString(stream, StandardCharsets.UTF_8).trim();
    } catch (NoSuchKeyException e) {
      return null;
    }

    UploadInfo parentInfo = getUploadInfo(new UploadId(parentIdStr));
    if (parentInfo == null || !objectExists(buildObjectKey(parentIdStr))) {
      // Self-cleaning: delete dangling checksum index object
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

  // PRIVATE HELPER METHODS (Single Level of Abstraction)

  /** Fetches upload info for given ID and validates size bounds. */
  private UploadInfo fetchAndValidateUpload(UploadId uploadId)
      throws UploadNotFoundException, TusException, IOException {
    UploadInfo info = getUploadInfo(uploadId);
    if (info == null) {
      throw new UploadNotFoundException("Upload with ID " + uploadId + " was not found");
    }
    validateUploadLimits(info);
    return info;
  }

  /** Validates upload length against max and min bounds. */
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
   * Checks for an existing incomplete .part object in S3 and prepends its content to incoming
   * stream.
   */
  private InputStream prepareStreamWithExistingIncompletePart(
      String partObjectKey, InputStream inputStream) throws IOException {
    try {
      HeadObjectResponse partHead =
          s3Client.headObject(
              HeadObjectRequest.builder().bucket(bucket).key(partObjectKey).build());
      if (partHead != null) {
        ResponseInputStream<GetObjectResponse> partStream =
            s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(partObjectKey).build());
        File tempPrependedFile =
            File.createTempFile("tus-s3-prep-", ".tmp", temporaryDirectory.toFile());
        tempPrependedFile.deleteOnExit();

        try (FileOutputStream fos = new FileOutputStream(tempPrependedFile)) {
          IOUtils.copy(partStream, fos);
        }
        deleteObjectQuietly(partObjectKey);
        return new SequenceInputStream(new FileInputStream(tempPrependedFile), inputStream);
      }
    } catch (NoSuchKeyException ignored) {
      // Normal case: no leftover .part object
    }
    return inputStream;
  }

  /** Ensures valid multipart upload ID exists; initiates a new one if missing. */
  private String ensureMultipartUploadId(UploadInfo info, String objectKey) {
    String multipartUploadId = info.getStorageUploadId();
    if (multipartUploadId == null) {
      CreateMultipartUploadResponse createResponse =
          createS3MultipartUpload(objectKey, info.getFileMimeType());
      multipartUploadId = createResponse.uploadId();
      info.setStorageUploadId(multipartUploadId);
    }
    return multipartUploadId;
  }

  /** Executes S3 CreateMultipartUpload request with content type header. */
  private CreateMultipartUploadResponse createS3MultipartUpload(String objectKey, String mimeType) {
    CreateMultipartUploadRequest.Builder builder =
        CreateMultipartUploadRequest.builder().bucket(bucket).key(objectKey);
    if (mimeType != null) {
      builder.contentType(mimeType);
    }
    return s3Client.createMultipartUpload(builder.build());
  }

  /** Reads incoming stream chunks into temporary files, uploading completed 5MB+ parts to S3. */
  private AppendResult processPayloadChunks(
      UploadInfo info,
      InputStream streamToRead,
      String objectKey,
      String multipartUploadId,
      String partObjectKey,
      List<CompletedPart> existingParts)
      throws IOException, MaxAppendSizeExceededException {

    int nextPartNumber = existingParts.size() + 1;
    List<CompletedPart> allParts = new ArrayList<>(existingParts);
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

      // S3 requirement: parts must be >= 5MB UNLESS it is the final completing part
      if (chunkBytesWritten >= minPartSize || (streamFinished && isUploadComplete)) {
        uploadPartToS3(
            objectKey,
            multipartUploadId,
            nextPartNumber,
            tempChunkFile,
            chunkBytesWritten,
            allParts);
        nextPartNumber++;
      } else {
        // Leftover chunk < 5MB and upload not complete -> store as .part object in S3
        storeIncompletePartToS3(partObjectKey, tempChunkFile, chunkBytesWritten);
      }
    }

    return new AppendResult(totalBytesAppended, allParts);
  }

  /**
   * Uploads a single part file to S3 multipart upload and appends its ETag to completed parts list.
   */
  private void uploadPartToS3(
      String objectKey,
      String multipartUploadId,
      int partNumber,
      File tempChunkFile,
      long chunkLength,
      List<CompletedPart> allParts)
      throws IOException {

    try (FileInputStream fis = new FileInputStream(tempChunkFile)) {
      UploadPartResponse partResponse =
          s3Client.uploadPart(
              UploadPartRequest.builder()
                  .bucket(bucket)
                  .key(objectKey)
                  .uploadId(multipartUploadId)
                  .partNumber(partNumber)
                  .contentLength(chunkLength)
                  .build(),
              RequestBody.fromInputStream(fis, chunkLength));

      allParts.add(
          CompletedPart.builder().partNumber(partNumber).eTag(partResponse.eTag()).build());
    } finally {
      tempChunkFile.delete();
    }
  }

  /** Writes a sub-5MB chunk to S3 as a temporary .part object. */
  private void storeIncompletePartToS3(String partObjectKey, File tempChunkFile, long chunkLength)
      throws IOException {
    try (FileInputStream fis = new FileInputStream(tempChunkFile)) {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(partObjectKey).build(),
          RequestBody.fromInputStream(fis, chunkLength));
    } finally {
      tempChunkFile.delete();
    }
  }

  /** Completes S3 multipart upload if all expected bytes have been received. */
  private void finalizeCompletedUploadIfFinished(
      UploadInfo info,
      String objectKey,
      String multipartUploadId,
      List<CompletedPart> allParts,
      long newOffset) {

    if (info.getLength() != null && newOffset >= info.getLength()) {
      s3Client.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(multipartUploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(allParts).build())
              .build());

      if (isUploadDeduplicationEnabled()
          && info.getChecksum() != null
          && info.getChecksumAlgorithm() != null
          && info.getDuplicatesUploadId() == null) {
        putChecksumIndex(info.getChecksum(), info.getChecksumAlgorithm(), info.getId().toString());
      }
    }
  }

  /** Fetches data object stream or .part stream for a given upload ID. */
  private InputStream fetchS3ByteStream(UploadId id, UploadInfo info)
      throws UploadNotFoundException {
    String objectKey = buildObjectKey(id.toString());
    try {
      return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (NoSuchKeyException e) {
      String partKey = buildIncompletePartKey(id.toString());
      try {
        return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(partKey).build());
      } catch (NoSuchKeyException ex) {
        if (info != null && (info.getOffset() == null || info.getOffset() == 0L)) {
          return new java.io.ByteArrayInputStream(new byte[0]);
        }
        throw new UploadNotFoundException("Uploaded bytes object not found for ID " + id);
      }
    }
  }

  /** Truncates bytes from a completed final S3 data object. */
  private void truncateFromCompletedObject(String objectKey, String partKey, long newOffset)
      throws IOException {
    if (newOffset > 0) {
      try (ResponseInputStream<GetObjectResponse> objStream =
          s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())) {
        byte[] remainingBytes = new byte[(int) newOffset];
        IOUtils.readFully(objStream, remainingBytes);
        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(partKey).build(),
            RequestBody.fromBytes(remainingBytes));
      }
    }
    deleteObjectQuietly(objectKey);
  }

  /** Truncates bytes from an incomplete .part S3 object buffer. */
  private void truncateFromIncompletePart(String partKey, long byteCount) {
    try {
      HeadObjectResponse head =
          s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(partKey).build());
      long partSize = head.contentLength();

      if (byteCount >= partSize) {
        deleteObjectQuietly(partKey);
      } else {
        ResponseInputStream<GetObjectResponse> partStream =
            s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(partKey).build());
        byte[] bytes = IOUtils.toByteArray(partStream);
        int newLength = (int) (bytes.length - byteCount);

        s3Client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(partKey).build(),
            RequestBody.fromBytes(java.util.Arrays.copyOf(bytes, newLength)));
      }
    } catch (NoSuchKeyException ignored) {
      // Normal state: incomplete part object not present
    } catch (Exception e) {
      log.debug("Error truncating incomplete part object {}", partKey, e);
    }
  }

  /** Calculates authoritative offset by querying S3 ListParts and .part object. */
  private void calculateAndSetOffset(UploadInfo info) {
    String id = info.getId().toString();
    String objectKey = buildObjectKey(id);
    String partKey = buildIncompletePartKey(id);
    String multipartUploadId = info.getStorageUploadId();

    long offset = calculateCurrentOffset(objectKey, multipartUploadId, partKey);
    info.setOffset(offset);
  }

  /** Sums byte lengths of all uploaded S3 parts and incomplete .part buffer. */
  private long calculateCurrentOffset(String objectKey, String multipartUploadId, String partKey) {
    long offset = 0;

    if (multipartUploadId != null) {
      try {
        ListPartsResponse listPartsResponse =
            s3Client.listParts(
                ListPartsRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .uploadId(multipartUploadId)
                    .build());
        for (Part part : listPartsResponse.parts()) {
          offset += part.size();
        }
      } catch (NoSuchUploadException e) {
        if (objectExists(objectKey)) {
          try {
            HeadObjectResponse head =
                s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return head.contentLength();
          } catch (Exception ignored) {
          }
        }
      } catch (Exception e) {
        log.debug("Error listing parts for object {}", objectKey, e);
      }
    }

    try {
      HeadObjectResponse partHead =
          s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(partKey).build());
      if (partHead != null && partHead.contentLength() != null) {
        offset += partHead.contentLength();
      }
    } catch (NoSuchKeyException ignored) {
    } catch (Exception e) {
      log.debug("Error reading head for incomplete part object {}", partKey, e);
    }

    return offset;
  }

  /** Lists completed parts for an active S3 multipart upload. */
  private List<CompletedPart> fetchCompletedParts(String objectKey, String multipartUploadId) {
    if (multipartUploadId == null) {
      return Collections.emptyList();
    }
    try {
      ListPartsResponse response =
          s3Client.listParts(
              ListPartsRequest.builder()
                  .bucket(bucket)
                  .key(objectKey)
                  .uploadId(multipartUploadId)
                  .build());
      List<CompletedPart> parts = new ArrayList<>();
      for (Part p : response.parts()) {
        parts.add(CompletedPart.builder().partNumber(p.partNumber()).eTag(p.eTag()).build());
      }
      return parts;
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  /** Computes optimal part size up to 5GB max based on total upload length. */
  private long calcOptimalPartSize(long totalSize) {
    long partSize = preferredPartSize;
    if (totalSize > 0 && totalSize / partSize >= MAX_MULTIPART_PARTS) {
      partSize = (totalSize / MAX_MULTIPART_PARTS) + 1;
    }
    return Math.max(minPartSize, Math.min(partSize, DEFAULT_MAX_PART_SIZE));
  }

  /** Writes a checksum index object to S3 for deduplication lookups. */
  private void putChecksumIndex(String checksum, ChecksumAlgorithm algorithm, String parentId) {
    String key = buildChecksumKey(checksum, algorithm);
    try {
      s3Client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(),
          RequestBody.fromString(parentId, StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.warn("Failed to write checksum index object to S3 key {}", key, e);
    }
  }

  /** Checks if an S3 object exists. */
  private boolean objectExists(String key) {
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
      return true;
    } catch (NoSuchKeyException e) {
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /** Aborts an active S3 multipart upload quietly. */
  private void abortMultipartUploadQuietly(String objectKey, String multipartUploadId) {
    try {
      s3Client.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .uploadId(multipartUploadId)
              .build());
    } catch (Exception e) {
      log.debug(
          "Abort multipart upload for object {} failed (may already be completed)", objectKey, e);
    }
  }

  /** Deletes an object quietly from S3 without throwing exceptions. */
  private void deleteObjectQuietly(String key) {
    if (key == null) {
      return;
    }
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {}", key, e);
    }
  }

  /** Ensures key prefixes are relative and end with a trailing slash. */
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

  private String buildChecksumKey(String checksum, ChecksumAlgorithm algorithm) {
    return checksumsPrefix + algorithm.getTusName().toLowerCase() + "/" + checksum;
  }

  /** Internal value object holding result of payload chunk append processing. */
  private static class AppendResult {
    final long totalBytesAppended;
    final List<CompletedPart> allParts;

    AppendResult(long totalBytesAppended, List<CompletedPart> allParts) {
      this.totalBytesAppended = totalBytesAppended;
      this.allParts = allParts;
    }
  }
}
