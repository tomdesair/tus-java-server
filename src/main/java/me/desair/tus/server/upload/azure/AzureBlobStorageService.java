package me.desair.tus.server.upload.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.Block;
import com.azure.storage.blob.models.BlockList;
import com.azure.storage.blob.models.BlockListType;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
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
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.exception.MinAppendSizeNotMetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Blob Storage implementation of {@link UploadStorageService}.
 *
 * <p><b>Azure Architecture Overview:</b>
 *
 * <ul>
 *   <li><b>Block Blobs & Staged Blocks</b>: Upload data is stored using Azure Block Blobs, which
 *       consist of up to 50,000 uncommitted staged blocks ({@code stageBlock}) that are committed
 *       atomically via {@code commitBlockList}.
 *   <li><b>Sub-Threshold Buffering ({@code .part})</b>: Appends smaller than the optimal block size
 *       (8 MB default) are buffered in a temporary {@code .part} blob under {@code metadata/} until
 *       a full block accumulates or the upload finishes.
 *   <li><b>Metadata ({@code .info})</b>: Upload metadata is stored as JSON-serialized {@link
 *       UploadInfo} objects under {@code metadata/<uploadId>.info}.
 *   <li><b>Checksum Deduplication Index</b>: Completed uploads are indexed by checksum under {@code
 *       checksums/<algorithm>/<hash>}. Duplicate uploads link to the parent upload ID.
 * </ul>
 */
public class AzureBlobStorageService implements UploadStorageService {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobStorageService.class);

  public static final String DEFAULT_OBJECT_PREFIX = "uploads/";
  public static final String DEFAULT_METADATA_PREFIX = "metadata/";
  public static final String DEFAULT_CHECKSUMS_PREFIX = "checksums/";
  public static final String DEFAULT_LOCKS_PREFIX = "locks/";

  // Azure Block Blob Constants & Auto-Calibration Limits
  private static final long MIN_BLOCK_SIZE = 4L * 1024 * 1024; // 4 MB (minimum recommended floor)
  private static final long DEFAULT_PREFERRED_BLOCK_SIZE = 8L * 1024 * 1024; // 8 MB
  private static final long MAX_BLOCK_SIZE = 4000L * 1024 * 1024; // 4000 MiB (Azure Blob limit)
  private static final int MAX_BLOCKS_PER_BLOB = 50_000; // Azure Block Blob block count limit

  private final BlobContainerClient containerClient;
  private final String uploadPrefix;
  private final String metadataPrefix;
  private final String checksumsPrefix;
  private final String locksPrefix;
  private final Path tempBufferDir;

  private long preferredBlockSize = DEFAULT_PREFERRED_BLOCK_SIZE;

  private Long maxUploadSize;
  private Long maxAppendSize;
  private Long minAppendSize;
  private Long minSize;
  private Long uploadExpirationPeriod;
  private boolean deduplicationEnabled = false;

  private UploadIdFactory idFactory = new UuidUploadIdFactory();
  private UploadConcatenationService concatenationService;

  /**
   * Constructs an {@link AzureBlobStorageService} using default object key prefixes and system temp
   * buffer directory.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   */
  public AzureBlobStorageService(BlobContainerClient containerClient) {
    this(
        containerClient,
        DEFAULT_OBJECT_PREFIX,
        DEFAULT_METADATA_PREFIX,
        DEFAULT_CHECKSUMS_PREFIX,
        DEFAULT_LOCKS_PREFIX,
        Paths.get(System.getProperty("java.io.tmpdir"), "tus-azure-buffer"));
  }

  /**
   * Constructs an {@link AzureBlobStorageService} with fully customizable prefixes and buffer path.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   * @param uploadPrefix Key prefix for final data objects
   * @param metadataPrefix Key prefix for metadata (.info and .part) objects
   * @param checksumsPrefix Key prefix for checksum deduplication index objects
   * @param locksPrefix Key prefix for distributed lock objects
   * @param tempBufferDir Local directory for staging chunk bytes before Azure upload
   */
  public AzureBlobStorageService(
      BlobContainerClient containerClient,
      String uploadPrefix,
      String metadataPrefix,
      String checksumsPrefix,
      String locksPrefix,
      Path tempBufferDir) {
    this.containerClient =
        Objects.requireNonNull(containerClient, "containerClient must not be null");
    this.uploadPrefix = sanitizePrefix(uploadPrefix);
    this.metadataPrefix = sanitizePrefix(metadataPrefix);
    this.checksumsPrefix = sanitizePrefix(checksumsPrefix);
    this.locksPrefix = sanitizePrefix(locksPrefix);
    this.tempBufferDir = Objects.requireNonNull(tempBufferDir, "tempBufferDir must not be null");

    try {
      Utils.ensureDirectoryExists(this.tempBufferDir);
    } catch (IOException e) {
      log.debug("Unable to ensure tempBufferDir exists: {}", e.getMessage());
    }
    Utils.cleanupTempFiles(this.tempBufferDir, "tus-azure-chunk-*.tmp", 24L * 3600_000L);

    this.concatenationService =
        new AzureBlobConcatenationService(containerClient, this.uploadPrefix, this);
  }

  @Override
  public void setIdFactory(UploadIdFactory idFactory) {
    this.idFactory = Objects.requireNonNull(idFactory, "idFactory must not be null");
  }

  @Override
  public String getUploadUri() {
    return idFactory.getUploadUri();
  }

  @Override
  public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
    Objects.requireNonNull(info, "UploadInfo must not be null");

    // 1. Generate new unique UploadId and initialize upload attributes
    UploadId id = idFactory.createId();
    info.setId(id);
    info.setOwnerKey(ownerKey);
    info.setOffset(0L);

    // Set storageUploadId to the actual Azure Blob identifier (e.g. "uploads/<id>")
    info.setStorageUploadId(getAzureBlobName(info));

    if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
      info.setExpirationTimestamp(System.currentTimeMillis() + uploadExpirationPeriod);
    }

    // 2. Persist JSON metadata object to Azure (.info blob)
    saveUploadInfo(info);

    // 3. If initial creation specifies 0 bytes, commit empty Block Blob immediately
    if (info.getLength() != null && info.getLength() == 0) {
      commitEmptyDataBlob(id);
      checkAndApplyDeduplication(info);
    }

    log.debug("Created new upload with ID {} for owner {}", id, ownerKey);
    return info;
  }

  @Override
  public UploadInfo append(UploadInfo upload, InputStream inputStream)
      throws IOException, TusException {
    Objects.requireNonNull(upload, "UploadInfo must not be null");
    Objects.requireNonNull(inputStream, "InputStream must not be null");

    // 1. Locate the incomplete sub-threshold .part blob buffer and query its current size
    BlobClient partBlob = containerClient.getBlobClient(metadataPrefix + upload.getId() + ".part");
    long existingPartSize = getPartBlobSize(partBlob);

    // 2. Auto-calibrate optimal block size (4 MB floor up to 4000 MiB limit based on upload length)
    long optimalBlockSize = calcOptimalBlockSize(upload.getLength());
    Long effectiveMaxAppendSize = getMaxAppendSize();

    // 3. Obtain Azure Block Blob client and fetch pre-existing committed block list
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(getAzureBlobName(upload)).getBlockBlobClient();
    List<String> blockIds = getCommittedBlockIds(blockBlobClient);

    long totalAppended = 0L;
    boolean streamFinished = false;

    File firstChunkFile = File.createTempFile("tus-azure-chunk-", ".tmp", tempBufferDir.toFile());
    try {
      // 4. Read first chunk from incoming payload stream into local disk buffer
      long firstChunkSize = readChunk(inputStream, firstChunkFile, optimalBlockSize);
      totalAppended += firstChunkSize;

      validateMaxAppendSize(totalAppended, effectiveMaxAppendSize);

      long newOffset = upload.getOffset() + firstChunkSize;
      boolean isUploadComplete = upload.getLength() != null && newOffset == upload.getLength();
      long totalBuffered = existingPartSize + firstChunkSize;

      if (firstChunkSize < optimalBlockSize && firstChunkSize >= 0) {
        streamFinished = true;
      }

      if (totalBuffered < optimalBlockSize && !isUploadComplete && streamFinished) {
        // Small append under block size threshold: buffer data to .part blob directly
        bufferToPartBlob(partBlob, existingPartSize, firstChunkFile, firstChunkSize);
      } else {
        // Data exceeds block size threshold: stage blocks to Azure Block Blob
        stagePartBlobIfPresent(partBlob, existingPartSize, blockBlobClient, blockIds);
        stageChunkFile(firstChunkFile, firstChunkSize, blockBlobClient, blockIds);

        // Process any remaining chunks from input stream
        if (!streamFinished) {
          totalAppended +=
              processRemainingChunks(
                  inputStream,
                  optimalBlockSize,
                  effectiveMaxAppendSize,
                  upload,
                  partBlob,
                  blockBlobClient,
                  blockIds,
                  totalAppended);
        }

        // Commit updated block ID list on Azure so staged blocks become committed and readable
        blockBlobClient.commitBlockList(blockIds, true);
      }

      validateMinAppendSize(totalAppended);

      // 5. Update UploadInfo offset, expiration timestamp, and optional deduplication state
      upload.setOffset(upload.getOffset() + totalAppended);
      if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
        upload.setExpirationTimestamp(System.currentTimeMillis() + uploadExpirationPeriod);
      }

      boolean finalComplete =
          upload.getLength() != null && upload.getOffset().equals(upload.getLength());
      if (finalComplete) {
        checkAndApplyDeduplication(upload);
      }

      saveUploadInfo(upload);
      return upload;
    } finally {
      deleteFileQuietly(firstChunkFile);
    }
  }

  @Override
  public UploadInfo getUploadInfo(String requestUri, String ownerKey) throws IOException {
    UploadId id = idFactory.readUploadId(requestUri);
    if (id == null) {
      return null;
    }

    UploadInfo info = getUploadInfo(id);
    if (info == null) {
      return null;
    }

    // Owner key validation for access isolation
    if (info.getOwnerKey() != null && !Objects.equals(ownerKey, info.getOwnerKey())) {
      return null;
    }

    return info;
  }

  @Override
  public UploadInfo getUploadInfo(UploadId id) throws IOException {
    if (id == null) {
      return null;
    }

    // Single GET call attempt: download .info blob directly, catching 404 BlobStorageException
    BlobClient infoBlob = containerClient.getBlobClient(metadataPrefix + id + ".info");
    try {
      byte[] bytes = infoBlob.downloadContent().toBytes();
      String json = new String(bytes, StandardCharsets.UTF_8);
      return UploadInfoJsonSerializer.deserialize(json);
    } catch (BlobStorageException e) {
      if (AzureUtils.parseErrorResponse(e) == AzureErrorType.BLOB_NOT_FOUND) {
        return null;
      }
      throw new IOException("Failed to download upload info for ID " + id, e);
    } catch (Exception e) {
      log.debug("Error deserializing upload info for ID {}: {}", id, e.getMessage());
      return null;
    }
  }

  public String getAzureBlobName(UploadInfo uploadInfo) {
    if (uploadInfo == null) {
      return null;
    }
    // Resolve duplicate child uploads dynamically to parent upload blob
    if (uploadInfo.getDuplicatesUploadId() != null) {
      return uploadPrefix + uploadInfo.getDuplicatesUploadId().toString();
    }
    return uploadPrefix + uploadInfo.getId().toString();
  }

  public String getAzureBlobName(String requestUri, String ownerKey) throws IOException {
    UploadInfo info = getUploadInfo(requestUri, ownerKey);
    return info != null ? getAzureBlobName(info) : null;
  }

  @Override
  public InputStream getUploadedBytes(String requestUri, String ownerKey)
      throws IOException, UploadNotFoundException {
    UploadInfo info = getUploadInfo(requestUri, ownerKey);
    if (info == null) {
      throw new UploadNotFoundException("Upload not found for URI " + requestUri);
    }
    return getUploadedBytes(info.getId());
  }

  @Override
  public InputStream getUploadedBytes(UploadId id) throws IOException {
    UploadInfo info = getUploadInfo(id);
    if (info == null) {
      return null;
    }
    // Read operations dynamically resolve duplicates to parent
    String targetBlobName = getAzureBlobName(info);
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(targetBlobName).getBlockBlobClient();
    BlobClient partBlob = containerClient.getBlobClient(metadataPrefix + info.getId() + ".part");

    InputStream committedStream = null;
    try {
      if (Boolean.TRUE.equals(blockBlobClient.exists())) {
        committedStream = blockBlobClient.openInputStream();
      }
    } catch (Exception ignored) {
      // Data blob does not exist yet (upload in progress under sub-threshold part buffer)
    }

    InputStream partStream = null;
    try {
      if (Boolean.TRUE.equals(partBlob.exists()) && partBlob.getProperties().getBlobSize() > 0) {
        partStream = partBlob.openInputStream();
      }
    } catch (Exception ignored) {
      // No sub-threshold part buffer present
    }

    if (committedStream != null && partStream != null) {
      return new SequenceInputStream(committedStream, partStream);
    } else if (committedStream != null) {
      return committedStream;
    } else if (partStream != null) {
      return partStream;
    } else {
      return new ByteArrayInputStream(new byte[0]);
    }
  }

  @Override
  public void copyUploadTo(UploadInfo uploadInfo, OutputStream outputStream)
      throws UploadNotFoundException, IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    Objects.requireNonNull(outputStream, "OutputStream must not be null");

    try (InputStream is = getUploadedBytes(uploadInfo.getId())) {
      if (is == null) {
        throw new UploadNotFoundException("Upload data not found for ID " + uploadInfo.getId());
      }
      IOUtils.copyLarge(is, outputStream);
    }
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    UploadId id = uploadInfo.getId();

    // 1. Delete committed data blob
    containerClient.getBlobClient(uploadPrefix + id).deleteIfExists();

    // 2. Delete incomplete sub-threshold .part blob
    containerClient.getBlobClient(metadataPrefix + id + ".part").deleteIfExists();

    // 3. Delete metadata .info blob
    containerClient.getBlobClient(metadataPrefix + id + ".info").deleteIfExists();

    // 4. Delete checksum deduplication index blob if present
    if (uploadInfo.getChecksum() != null && uploadInfo.getChecksumAlgorithm() != null) {
      String checksumKey =
          buildChecksumKey(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm());
      containerClient.getBlobClient(checksumKey).deleteIfExists();
    }

    // 5. Delete lock target and stop signal blobs (handling active lease exceptions gracefully)
    try {
      containerClient.getBlobClient(locksPrefix + id + ".stop").deleteIfExists();
    } catch (Exception ignored) {
    }

    try {
      containerClient.getBlobClient(locksPrefix + id + ".lock").deleteIfExists();
    } catch (Exception ignored) {
      // Lock blob may be actively leased by current request lock (Azure 412 LeaseIdMissing)
    }

    log.debug("Terminated upload with ID {}", id);
  }

  @Override
  public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    if (byteCount <= 0) {
      return;
    }

    // Write/modify operations MUST NOT resolve duplicates to parent.
    long currentOffset = uploadInfo.getOffset();
    long targetOffset = Math.max(0L, currentOffset - byteCount);

    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(getAzureBlobName(uploadInfo)).getBlockBlobClient();
    BlobClient partBlob =
        containerClient.getBlobClient(metadataPrefix + uploadInfo.getId() + ".part");

    long blockBlobSize = 0L;
    List<Block> committedBlocks = new ArrayList<>();
    try {
      BlockList blockList = blockBlobClient.listBlocks(BlockListType.COMMITTED);
      if (blockList != null && blockList.getCommittedBlocks() != null) {
        committedBlocks = blockList.getCommittedBlocks();
        for (Block b : committedBlocks) {
          blockBlobSize += b.getSizeLong();
        }
      }
    } catch (Exception ignored) {
      // Blob doesn't exist or has no committed blocks
    }

    if (targetOffset <= blockBlobSize) {
      // Truncation cuts into committed blocks: delete .part blob completely
      partBlob.deleteIfExists();

      if (targetOffset == 0) {
        blockBlobClient.deleteIfExists();
      } else {
        File tempFile =
            File.createTempFile("tus-azure-block-trim-", ".tmp", tempBufferDir.toFile());
        try {
          try (InputStream is =
                  BoundedInputStream.builder()
                      .setInputStream(blockBlobClient.openInputStream())
                      .setMaxCount(targetOffset)
                      .get();
              OutputStream os = new FileOutputStream(tempFile)) {
            IOUtils.copyLarge(is, os);
          }
          String newBlockId = generateBlockId(0);
          try (InputStream is = new java.io.BufferedInputStream(new FileInputStream(tempFile))) {
            blockBlobClient.stageBlock(newBlockId, is, tempFile.length());
          }
          blockBlobClient.commitBlockList(List.of(newBlockId), true);
        } finally {
          deleteFileQuietly(tempFile);
        }
      }
    } else {
      // Truncation only affects .part buffer: keep committed blocks, trim .part blob
      long newPartSize = targetOffset - blockBlobSize;
      if (newPartSize <= 0) {
        partBlob.deleteIfExists();
      } else {
        File tempFile = File.createTempFile("tus-azure-truncate-", ".tmp", tempBufferDir.toFile());
        try {
          try (InputStream is =
                  BoundedInputStream.builder()
                      .setInputStream(partBlob.openInputStream())
                      .setMaxCount(newPartSize)
                      .get();
              OutputStream os = new FileOutputStream(tempFile)) {
            IOUtils.copyLarge(is, os);
          }
          partBlob.upload(BinaryData.fromFile(tempFile.toPath()), true);
        } finally {
          deleteFileQuietly(tempFile);
        }
      }
    }

    uploadInfo.setOffset(targetOffset);
    saveUploadInfo(uploadInfo);
  }

  @Override
  public UploadInfo getUploadInfoByChecksum(String checksum, ChecksumAlgorithm algorithm)
      throws IOException {
    if (!isUploadDeduplicationEnabled() || checksum == null || algorithm == null) {
      return null;
    }

    String checksumKey = buildChecksumKey(checksum, algorithm);
    BlobClient checksumBlob = containerClient.getBlobClient(checksumKey);

    try {
      byte[] bytes = checksumBlob.downloadContent().toBytes();
      String parentIdStr = new String(bytes, StandardCharsets.UTF_8).trim();
      UploadId parentId = new UploadId(parentIdStr);
      UploadInfo parentInfo = getUploadInfo(parentId);

      // Self-cleaning index: if index points to missing/deleted upload, clean up stale index
      if (parentInfo == null) {
        checksumBlob.deleteIfExists();
        return null;
      }

      return parentInfo;
    } catch (BlobStorageException e) {
      if (AzureUtils.parseErrorResponse(e) == AzureErrorType.BLOB_NOT_FOUND) {
        return null;
      }
      throw new IOException("Error reading checksum index blob " + checksumKey, e);
    } catch (Exception e) {
      log.debug("Error retrieving upload info by checksum: {}", e.getMessage());
      return null;
    }
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    saveUploadInfo(uploadInfo);
  }

  @Override
  public void cleanupExpiredUploads(UploadLockingService lockingService) throws IOException {
    Utils.cleanupTempFiles(this.tempBufferDir, "tus-azure-chunk-*.tmp", 24L * 3600_000L);
    ListBlobsOptions options = new ListBlobsOptions().setPrefix(metadataPrefix);
    for (BlobItem item : containerClient.listBlobs(options, null)) {
      if (item.getName().endsWith(".info")) {
        String infoName = item.getName();
        String idStr =
            infoName.substring(metadataPrefix.length(), infoName.length() - ".info".length());
        UploadId id = new UploadId(idStr);
        UploadInfo info = getUploadInfo(id);
        if (info != null && info.isExpired()) {
          if (lockingService != null && lockingService.isLocked(id)) {
            log.debug("Skipping cleanup of expired upload {} because it is currently locked", id);
            continue;
          }
          log.info("Cleaning up expired upload with ID {}", id);
          try {
            terminateUpload(info);
          } catch (UploadNotFoundException ignored) {
          }
        }
      }
    }
  }

  public void cleanupExpiredUploads() throws IOException {
    cleanupExpiredUploads(null);
  }

  // --- Configuration Getters & Setters ---

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
  public void setUploadDeduplicationEnabled(boolean deduplicationEnabled) {
    this.deduplicationEnabled = deduplicationEnabled;
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

  public void setPreferredBlockSize(long preferredBlockSize) {
    if (preferredBlockSize < MIN_BLOCK_SIZE || preferredBlockSize > MAX_BLOCK_SIZE) {
      throw new IllegalArgumentException(
          "preferredBlockSize must be between " + MIN_BLOCK_SIZE + " and " + MAX_BLOCK_SIZE);
    }
    this.preferredBlockSize = preferredBlockSize;
  }

  public long getPreferredBlockSize() {
    return preferredBlockSize;
  }

  // --- Helper Methods ---

  /** Calculates auto-calibrated optimal block size based on total upload length. */
  private long calcOptimalBlockSize(Long totalLength) {
    long size = preferredBlockSize;
    if (totalLength != null && totalLength > 0 && totalLength / size >= MAX_BLOCKS_PER_BLOB) {
      size = (totalLength / MAX_BLOCKS_PER_BLOB) + 1;
    }
    return Math.max(MIN_BLOCK_SIZE, Math.min(size, MAX_BLOCK_SIZE));
  }

  /** Saves UploadInfo object as JSON in .info metadata blob. */
  private void saveUploadInfo(UploadInfo info) throws IOException {
    byte[] jsonBytes = UploadInfoJsonSerializer.serialize(info).getBytes(StandardCharsets.UTF_8);
    BlobClient infoBlob = containerClient.getBlobClient(metadataPrefix + info.getId() + ".info");
    infoBlob.upload(BinaryData.fromBytes(jsonBytes), true);
  }

  /** Gets size of existing .part blob using single HEAD call. */
  private long getPartBlobSize(BlobClient partBlob) {
    try {
      return partBlob.getProperties().getBlobSize();
    } catch (Exception e) {
      return 0L;
    }
  }

  /** Reads up to maxBytes from InputStream into target File. */
  private long readChunk(InputStream is, File targetFile, long maxBytes) throws IOException {
    long totalRead = 0L;
    byte[] buffer = new byte[8192];
    try (FileOutputStream fos = new FileOutputStream(targetFile)) {
      while (totalRead < maxBytes) {
        int lenToRead = (int) Math.min(buffer.length, maxBytes - totalRead);
        int read = is.read(buffer, 0, lenToRead);
        if (read == -1) {
          break;
        }
        fos.write(buffer, 0, read);
        totalRead += read;
      }
    }
    return totalRead;
  }

  /** Retrieves committed block IDs from Azure Block Blob. */
  private List<String> getCommittedBlockIds(BlockBlobClient blockBlobClient) {
    List<String> blockIds = new ArrayList<>();
    try {
      BlockList blockList = blockBlobClient.listBlocks(BlockListType.COMMITTED);
      if (blockList != null && blockList.getCommittedBlocks() != null) {
        for (Block block : blockList.getCommittedBlocks()) {
          blockIds.add(block.getName());
        }
      }
    } catch (Exception e) {
      log.debug("Could not retrieve committed block list: {}", e.getMessage());
    }
    return blockIds;
  }

  /** Validates effective max append size limit. */
  private void validateMaxAppendSize(long totalAppended, Long effectiveMaxAppendSize)
      throws MaxAppendSizeExceededException {
    if (effectiveMaxAppendSize != null && totalAppended > effectiveMaxAppendSize) {
      throw new MaxAppendSizeExceededException(
          "Append size "
              + totalAppended
              + " exceeds maximum allowed chunk size of "
              + effectiveMaxAppendSize);
    }
  }

  /** Validates min append size limit. */
  private void validateMinAppendSize(long totalAppended) throws MinAppendSizeNotMetException {
    if (minAppendSize != null && totalAppended < minAppendSize) {
      throw new MinAppendSizeNotMetException(
          "Append size " + totalAppended + " is less than minimum allowed of " + minAppendSize);
    }
  }

  /** Stages pre-existing .part blob as a Block Blob block if present. */
  private void stagePartBlobIfPresent(
      BlobClient partBlob,
      long existingPartSize,
      BlockBlobClient blockBlobClient,
      List<String> blockIds)
      throws IOException {
    if (existingPartSize > 0) {
      String partBlockId = generateBlockId(blockIds.size());
      try (InputStream partIs = partBlob.openInputStream()) {
        blockBlobClient.stageBlock(partBlockId, partIs, existingPartSize);
      }
      blockIds.add(partBlockId);
      partBlob.deleteIfExists();
    }
  }

  /** Stages local chunk temp file as a Block Blob block. */
  private void stageChunkFile(
      File chunkFile, long chunkSize, BlockBlobClient blockBlobClient, List<String> blockIds)
      throws IOException {
    if (chunkSize > 0) {
      String chunkBlockId = generateBlockId(blockIds.size());
      try (InputStream chunkIs = new java.io.BufferedInputStream(new FileInputStream(chunkFile))) {
        blockBlobClient.stageBlock(chunkBlockId, chunkIs, chunkSize);
      }
      blockIds.add(chunkBlockId);
    }
  }

  /** Processes remaining payload chunks from stream until EOF. */
  private long processRemainingChunks(
      InputStream inputStream,
      long optimalBlockSize,
      Long effectiveMaxAppendSize,
      UploadInfo upload,
      BlobClient partBlob,
      BlockBlobClient blockBlobClient,
      List<String> blockIds,
      long currentTotalAppended)
      throws IOException, MaxAppendSizeExceededException {
    long additionalAppended = 0L;
    boolean streamFinished = false;

    while (!streamFinished) {
      File chunkFile = File.createTempFile("tus-azure-chunk-", ".tmp", tempBufferDir.toFile());
      try {
        long chunkSize = readChunk(inputStream, chunkFile, optimalBlockSize);
        if (chunkSize <= 0) {
          break;
        }

        additionalAppended += chunkSize;
        long totalAppendedSoFar = currentTotalAppended + additionalAppended;
        validateMaxAppendSize(totalAppendedSoFar, effectiveMaxAppendSize);

        long currentOffset = upload.getOffset() + totalAppendedSoFar;
        boolean complete = upload.getLength() != null && currentOffset == upload.getLength();

        if (chunkSize < optimalBlockSize && !complete) {
          bufferToPartBlob(partBlob, 0L, chunkFile, chunkSize);
        } else {
          stageChunkFile(chunkFile, chunkSize, blockBlobClient, blockIds);
        }
      } finally {
        deleteFileQuietly(chunkFile);
      }
    }
    return additionalAppended;
  }

  /** Buffers incoming data to temporary .part blob when under block threshold. */
  private void bufferToPartBlob(
      BlobClient partBlob, long existingPartSize, File tempFile, long appendSize)
      throws IOException {
    if (existingPartSize == 0) {
      partBlob.upload(BinaryData.fromFile(tempFile.toPath()), true);
    } else {
      File combinedTemp = File.createTempFile("tus-azure-part-", ".tmp", tempBufferDir.toFile());
      try {
        try (InputStream partIs = partBlob.openInputStream();
            InputStream tempIs = new FileInputStream(tempFile);
            SequenceInputStream seqIs = new SequenceInputStream(partIs, tempIs);
            FileOutputStream fos = new FileOutputStream(combinedTemp)) {
          IOUtils.copyLarge(seqIs, fos);
        }
        partBlob.upload(BinaryData.fromFile(combinedTemp.toPath()), true);
      } finally {
        deleteFileQuietly(combinedTemp);
      }
    }
  }

  /** Commits empty block list for 0-byte upload completion. */
  private void commitEmptyDataBlob(UploadId id) {
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(uploadPrefix + id).getBlockBlobClient();
    blockBlobClient.commitBlockList(new ArrayList<>(), true);
  }

  /** Generates Base64 encoded block ID matching Azure Block Blob standards. */
  private String generateBlockId(int index) {
    String idString = String.format("block-%06d", index);
    return Base64.getEncoder().encodeToString(idString.getBytes(StandardCharsets.UTF_8));
  }

  /** Checks for deduplication match and links upload if duplicate. */
  private void checkAndApplyDeduplication(UploadInfo uploadInfo) throws IOException {
    if (!isUploadDeduplicationEnabled()
        || uploadInfo == null
        || uploadInfo.getChecksum() == null
        || uploadInfo.getChecksumAlgorithm() == null) {
      return;
    }

    String checksum = uploadInfo.getChecksum();
    ChecksumAlgorithm algorithm = uploadInfo.getChecksumAlgorithm();

    UploadInfo existingUpload = getUploadInfoByChecksum(checksum, algorithm);
    if (existingUpload != null && !existingUpload.getId().equals(uploadInfo.getId())) {
      log.info(
          "Found duplicate upload with checksum {}/{} (parent ID {})",
          algorithm,
          checksum,
          existingUpload.getId());
      uploadInfo.setDuplicatesUploadId(existingUpload.getId());
      containerClient.getBlobClient(uploadPrefix + uploadInfo.getId()).deleteIfExists();
    } else {
      String checksumKey = buildChecksumKey(checksum, algorithm);
      BlobClient checksumBlob = containerClient.getBlobClient(checksumKey);
      byte[] idBytes = uploadInfo.getId().toString().getBytes(StandardCharsets.UTF_8);
      checksumBlob.upload(BinaryData.fromBytes(idBytes), true);
    }
  }

  /** Builds checksum index key path. */
  private String buildChecksumKey(String checksum, ChecksumAlgorithm algorithm) {
    String algorithmName = algorithm != null ? algorithm.getTusName().toLowerCase() : "unknown";
    return checksumsPrefix + algorithmName + "/" + checksum;
  }

  /** Sanitizes object key prefix format. */
  private String sanitizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    String result = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    return result.endsWith("/") ? result : result + "/";
  }

  /** Deletes local file suppressing exceptions. */
  private void deleteFileQuietly(File file) {
    if (file != null && file.exists()) {
      try {
        Files.delete(file.toPath());
      } catch (Exception ignored) {
      }
    }
  }
}
