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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.exception.MinAppendSizeNotMetException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.AbstractUploadStorageService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UuidUploadIdFactory;
import me.desair.tus.server.upload.checksum.DeduplicationService;
import me.desair.tus.server.upload.util.AsyncChunkUploader;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.UploadInfoJsonSerializer;
import me.desair.tus.server.util.Utils;
import org.apache.commons.io.FileUtils;
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
 *   <li><b>Direct Block Staging</b>: Appends are streamed directly into staged blocks and committed
 *       atomically upon arrival without temporary intermediate blobs.
 *   <li><b>Metadata ({@code .info})</b>: Upload metadata is stored as JSON-serialized {@link
 *       UploadInfo} objects under {@code metadata/<uploadId>.info}.
 *   <li><b>Checksum Deduplication Index</b>: Completed uploads are indexed by checksum under {@code
 *       checksums/<algorithm>/<hash>}. Duplicate uploads link to the parent upload ID.
 * </ul>
 */
public class AzureBlobStorageService extends AbstractUploadStorageService {

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

  private final AzureBlobChecksumIndex checksumIndex;
  private DeduplicationService deduplicationService;

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
   * @param metadataPrefix Key prefix for metadata (.info) objects
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
    this.uploadPrefix = Utils.sanitizePrefix(uploadPrefix);
    this.metadataPrefix = Utils.sanitizePrefix(metadataPrefix);
    this.checksumsPrefix = Utils.sanitizePrefix(checksumsPrefix);
    this.locksPrefix = Utils.sanitizePrefix(locksPrefix);
    this.tempBufferDir = Objects.requireNonNull(tempBufferDir, "tempBufferDir must not be null");

    try {
      Utils.ensureDirectoryExists(this.tempBufferDir);
    } catch (IOException e) {
      log.debug("Unable to ensure tempBufferDir exists: {}", e.getMessage());
    }
    Utils.cleanupTempFiles(this.tempBufferDir, "tus-azure-chunk-*.tmp", 24L * 3600_000L);

    this.idFactory = new UuidUploadIdFactory();
    this.drainTimeout = Duration.ofSeconds(55);
    this.uploadExecutor = Utils.createUploadExecutor(cloudUploadThreadPoolSize, "tus-azure-upload");

    this.concatenationService =
        new AzureBlobConcatenationService(containerClient, this.uploadPrefix, this);

    this.checksumIndex = new AzureBlobChecksumIndex(this.containerClient, this.checksumsPrefix);
    this.deduplicationService = new DeduplicationService(this.checksumIndex, this);
    this.uploadLockingService = new AzureBlobLockingService(this.containerClient, this.locksPrefix);
    this.deduplicationService.setUploadLockingService(this.uploadLockingService);
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
    info.setUploadPartKeys(new ArrayList<>());

    // Set storageUploadId to the actual Azure Blob identifier (e.g. "uploads/<id>")
    info.setStorageUploadId(getAzureBlobName(info));

    if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
      info.updateExpiration(uploadExpirationPeriod);
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

    if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
      upload.updateExpiration(uploadExpirationPeriod);
    }

    // 1. Obtain Azure Block Blob client and fetch pre-existing committed block list.
    // Write and modify operations must strictly target the upload's own physical data blob,
    // never resolving duplicates to parent blobs, to protect completed parent files.
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(getOwnAzureBlobName(upload)).getBlockBlobClient();
    List<String> recordedPartKeys =
        upload.getUploadPartKeys() != null ? upload.getUploadPartKeys() : Collections.emptyList();
    List<String> blockIds;
    if (!recordedPartKeys.isEmpty()) {
      // Reuse the authoritative committed block IDs recorded in UploadInfo metadata. This avoids
      // an extra synchronous listBlocks() network call on every PATCH request and guarantees that
      // a transient Azure error cannot return an empty list and overwrite prior blocks.
      blockIds = new ArrayList<>(recordedPartKeys);
    } else if (upload.getOffset() != null && upload.getOffset() > 0L) {
      // Recovery path: offset > 0 but metadata has no recorded part keys. Fetch committed blocks
      // from Azure, propagating non-404 errors so we never commit an empty baseline over data.
      blockIds = getCommittedBlockIds(blockBlobClient);
    } else {
      blockIds = new ArrayList<>();
    }

    // 2. Validate block budget: Azure Block Blobs support up to 50,000 blocks
    validateRemainingBlockBudget(upload, blockIds.size());

    // 3. Auto-calibrate optimal chunk buffer size (4 MB floor up to 4000 MiB limit based on length)
    long optimalBlockSize = calcOptimalBlockSize(upload.getLength());
    Long effectiveMaxAppendSize = getMaxAppendSize();

    long initialOffset = upload.getOffset();
    int initialBlockCount = blockIds.size();
    long totalAppended = 0L;
    IOException streamException = null;
    IOException drainException = null;
    TusException pendingTusException = null;

    List<String> plannedBlockIds = new ArrayList<>();
    List<Long> plannedChunkSizes = new ArrayList<>();

    InterruptibleInputStream interruptibleStream = Utils.toInterruptibleStream(inputStream);
    try (AsyncChunkUploader uploader =
        new AsyncChunkUploader(uploadExecutor, drainTimeout.toMillis(), interruptibleStream)) {
      // 4. Read incoming stream in chunks, staging blocks directly to Azure Block Blob
      while (true) {
        File chunkFile = null;
        boolean handedOff = false;
        try {
          long maxBytesToRead = optimalBlockSize;
          if (effectiveMaxAppendSize != null) {
            // Read at most 1 byte beyond the remaining allowed append size so oversized streams
            // are interrupted immediately without buffering large amounts of excess data to disk.
            long remainingAllowed = Math.max(0L, effectiveMaxAppendSize - totalAppended);
            maxBytesToRead = Math.min(optimalBlockSize, remainingAllowed + 1L);
          }

          chunkFile = Files.createTempFile(tempBufferDir, "tus-azure-chunk-", ".tmp").toFile();
          // Pass interruptibleStream so AsyncChunkUploader's drain timeout or lock contention can
          // interrupt blocked reads cleanly even when the caller passed a raw InputStream.
          ReadChunkResult chunkResult = readChunk(interruptibleStream, chunkFile, maxBytesToRead);
          long chunkSize = chunkResult.bytesRead;
          if (chunkResult.exception != null) {
            streamException = chunkResult.exception;
          }

          if (chunkSize <= 0) {
            break;
          }

          // Validate append size against effectiveMaxAppendSize. If exceeded, throws
          // MaxAppendSizeExceededException, which is caught below as pendingTusException to stop
          // reading while still committing any previously staged blocks without data loss.
          validateMaxAppendSize(totalAppended + chunkSize, effectiveMaxAppendSize);

          int plannedIndex = blockIds.size() + plannedBlockIds.size();
          // Validate chunk against remaining block budget
          validateRemainingBlockBudget(upload, plannedIndex);

          String blockId = generateBlockId(plannedIndex);
          plannedBlockIds.add(blockId);
          plannedChunkSizes.add(chunkSize);
          totalAppended += chunkSize;

          File fileToUpload = chunkFile;
          uploader.submitChunk(
              chunkFile,
              chunkSize,
              blockId,
              () -> stageBlock(blockBlobClient, blockId, fileToUpload, chunkSize));
          handedOff = true;

          if (streamException != null) {
            break;
          }
        } catch (IOException e) {
          streamException = e;
          break;
        } catch (TusException te) {
          pendingTusException = te;
          break;
        } finally {
          if (!handedOff) {
            FileUtils.deleteQuietly(chunkFile);
          }
        }
      }

      // Drain remaining staged chunks with configured timeout (defaults to 55s, lock wait - 5s).
      // Catch drainException so any chunks confirmed uploaded before timeout or error
      // are committed and the metadata offset is preserved without loss.
      try {
        uploader.drainAndComplete();
      } catch (IOException e) {
        drainException = e;
      } finally {
        int confirmed = Math.min(uploader.getConfirmedCount(), plannedBlockIds.size());
        for (int i = 0; i < confirmed; i++) {
          blockIds.add(plannedBlockIds.get(i));
        }
      }
    }

    // Calculate actual confirmed bytes appended
    long confirmedAppended = 0L;
    for (int i = 0; i < blockIds.size() - initialBlockCount; i++) {
      confirmedAppended += plannedChunkSizes.get(i);
    }

    // 5. Commit any newly staged blocks in batch (1 single commit call for entire request)
    // Batching block commits into a single call at the end eliminates redundant network
    // round-trips for every chunk, drastically improving performance. In addition, committing
    // here before throwing any pending stream or limit exception guarantees zero data loss
    // if client pauses, network drops, or limits are hit midway.
    if (blockIds.size() > initialBlockCount) {
      try {
        blockBlobClient.commitBlockList(blockIds, true);
        upload.setOffset(initialOffset + confirmedAppended);
        upload.setUploadPartKeys(new ArrayList<>(blockIds));
        if (uploadExpirationPeriod != null && uploadExpirationPeriod > 0) {
          upload.setExpirationTimestamp(System.currentTimeMillis() + uploadExpirationPeriod);
        }

        boolean finalComplete =
            upload.getLength() != null && upload.getOffset().equals(upload.getLength());
        boolean deduplicated = false;
        if (finalComplete) {
          deduplicated = checkAndApplyDeduplication(upload);
        }

        if (!deduplicated) {
          saveUploadInfo(upload);
        }
      } catch (Exception e) {
        log.error(
            "Failed to commit staged blocks or save metadata for upload ID {}", upload.getId(), e);
        if (e instanceof IOException) {
          throw (IOException) e;
        }
        throw new IOException("Failed to commit staged blocks for upload ID " + upload.getId(), e);
      }
    }

    if (drainException != null) {
      throw drainException;
    }

    if (streamException != null) {
      throw streamException;
    }

    if (pendingTusException != null) {
      throw pendingTusException;
    }

    // 6. Validate minimum append size constraints if configured
    // Per RUFH §4.1.4: "This limit does not apply to upload creation requests with no content,
    // or to requests completing the upload by including the Upload-Complete: ?1 header field."
    boolean isCompletingOrEmpty =
        !upload.isUploadInProgress()
            || (upload.getLength() != null
                && upload.getOffset() != null
                && upload.getOffset() >= upload.getLength());
    if (!isCompletingOrEmpty) {
      validateMinAppendSize(confirmedAppended);
    }

    return upload;
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

  /**
   * Returns the Azure Block Blob name for the upload's own physical data blob without dynamically
   * resolving deduplication links. Write and truncate operations must strictly target the upload's
   * own physical data blob rather than resolving duplicates to parent blobs, preventing accidental
   * truncation or modification of parent files.
   *
   * @param uploadInfo The upload metadata
   * @return The physical blob name for this upload
   */
  public String getOwnAzureBlobName(UploadInfo uploadInfo) {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return null;
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

  @SuppressWarnings("java:S2095") // InputStream is returned to caller for external management
  @Override
  public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
    UploadInfo info = getUploadInfo(id);
    if (info == null) {
      return null;
    }
    // Read operations dynamically resolve duplicates to parent
    String targetBlobName = getAzureBlobName(info);
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(targetBlobName).getBlockBlobClient();

    try {
      if (AzureUtils.blobExists(blockBlobClient)) {
        return blockBlobClient.openInputStream();
      }
    } catch (Exception ignored) {
      // Data blob does not exist yet (e.g. 0-byte upload or immediately after creation)
    }

    if (info.getOffset() != null && info.getOffset() > 0L) {
      throw new UploadNotFoundException(
          "Uploaded bytes blob " + targetBlobName + " not found for ID " + id);
    }

    return new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public void terminateUpload(UploadInfo uploadInfo) throws UploadNotFoundException, IOException {
    if (uploadInfo == null || uploadInfo.getId() == null) {
      return;
    }
    UploadId id = uploadInfo.getId();

    boolean deleteData = true;
    if (isUploadDeduplicationEnabled() && deduplicationService != null) {
      deleteData = deduplicationService.removeUploadFromIndex(uploadInfo);
    }

    if (deleteData) {
      deleteData(id);
    }
    deleteMetadata(id);

    log.debug("Terminated upload with ID {}", id);
  }

  @Override
  public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
      throws UploadNotFoundException, IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    if (byteCount <= 0) {
      return;
    }

    // Write and modify operations must strictly target the upload's own physical data blob,
    // never resolving duplicates to parent blobs, to protect completed parent files.
    long currentOffset = uploadInfo.getOffset();
    long targetOffset = Math.max(0L, currentOffset - byteCount);

    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(getOwnAzureBlobName(uploadInfo)).getBlockBlobClient();

    if (targetOffset == 0) {
      AzureUtils.deleteBlobQuietly(blockBlobClient);
      uploadInfo.setUploadPartKeys(new ArrayList<>());
    } else {
      File tempFile = Files.createTempFile(tempBufferDir, "tus-azure-block-trim-", ".tmp").toFile();
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
        uploadInfo.setUploadPartKeys(new ArrayList<>(Collections.singletonList(newBlockId)));
      } finally {
        FileUtils.deleteQuietly(tempFile);
      }
    }

    uploadInfo.setOffset(targetOffset);
    saveUploadInfo(uploadInfo);
  }

  @Override
  public void update(UploadInfo uploadInfo) throws IOException {
    Objects.requireNonNull(uploadInfo, "UploadInfo must not be null");
    saveUploadInfo(uploadInfo);

    if (uploadInfo.getDuplicatesUploadId() != null && deduplicationService != null) {
      deduplicationService.syncParentExpirationIfChild(uploadInfo);
    }

    if (isUploadDeduplicationEnabled()
        && uploadInfo.getChecksum() != null
        && !uploadInfo.isUploadInProgress()
        && uploadInfo.getDuplicatesUploadId() == null) {
      checkAndApplyDeduplication(uploadInfo);
    }
  }

  @Override
  public boolean dataExists(UploadId id) {
    if (id == null) {
      return false;
    }
    return AzureUtils.blobExists(containerClient, uploadPrefix + id);
  }

  @Override
  public void deleteData(UploadId id) {
    if (id != null) {
      AzureUtils.deleteBlobQuietly(containerClient, uploadPrefix + id);
    }
  }

  private void deleteMetadata(UploadId id) {
    if (id != null) {
      AzureUtils.deleteBlobQuietly(containerClient, metadataPrefix + id + ".info");
    }
  }

  @Override
  public void cleanupExpiredUploads() throws IOException {
    Utils.cleanupTempFiles(this.tempBufferDir, "tus-azure-chunk-*.tmp", 24L * 3600_000L);
    ListBlobsOptions options = new ListBlobsOptions().setPrefix(metadataPrefix);
    OffsetDateTime expirationCutoff = AzureUtils.calculateExpirationCutoff(uploadExpirationPeriod);
    for (BlobItem item : containerClient.listBlobs(options, null)) {
      if (item.getName().endsWith(".info")) {
        // Pre-filter using LIST Last-Modified metadata to avoid an O(N) HTTP GET storm for
        // recently updated or created uploads that cannot yet have expired.
        if (expirationCutoff != null && item.getProperties() != null) {
          OffsetDateTime lastModified = item.getProperties().getLastModified();
          if (lastModified != null && lastModified.isAfter(expirationCutoff)) {
            continue;
          }
        }
        String infoName = item.getName();
        String idStr =
            infoName.substring(metadataPrefix.length(), infoName.length() - ".info".length());
        UploadId id = new UploadId(idStr);
        UploadInfo info = getUploadInfo(id);
        // Only clean up in-progress uploads; completed uploads must be preserved per Tus 1.0.0 spec
        if (info != null && info.isExpired() && info.isUploadInProgress()) {
          if (this.uploadLockingService.isLocked(id)) {
            log.debug("Skipping cleanup of expired upload {} because it is currently locked", id);
            continue;
          }
          log.info("Cleaning up expired upload with ID {}", id);
          try {
            terminateUpload(info);
          } catch (UploadNotFoundException ignored) {
            // Upload was already terminated or deleted concurrently; still ensure lock is cleaned
          }
          this.uploadLockingService.cleanupLock(id);
        } else if (info == null) {
          // Corrupted or missing metadata object: clean up any orphaned lock
          this.uploadLockingService.cleanupLock(id);
        }
      }
    }

    if (isUploadDeduplicationEnabled() && deduplicationService != null) {
      deduplicationService.pruneOrphanedChecksumIndices();
    }
  }

  @Override
  @Deprecated
  public void cleanupExpiredUploads(UploadLockingService lockingService) throws IOException {
    setUploadLockingService(Utils.getLockingServiceOrDefault(lockingService));
    cleanupExpiredUploads();
  }

  // --- Configuration Getters & Setters ---

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

  @Override
  public void close() throws IOException {
    super.close();
    if (uploadLockingService != null) {
      uploadLockingService.close();
    }
  }

  @Override
  protected DeduplicationService getDeduplicationService() {
    return this.deduplicationService;
  }

  // --- Helper Methods ---

  /** Calculates auto-calibrated optimal block size based on total upload length. */
  long calcOptimalBlockSize(Long totalLength) {
    long size = preferredBlockSize;
    if (totalLength != null && totalLength > 0 && totalLength / size >= MAX_BLOCKS_PER_BLOB) {
      size = (totalLength / MAX_BLOCKS_PER_BLOB) + 1;
    }
    return Math.max(MIN_BLOCK_SIZE, Math.min(size, MAX_BLOCK_SIZE));
  }

  /** Saves UploadInfo object as JSON in .info metadata blob. */
  private void saveUploadInfo(UploadInfo info) throws IOException {
    String infoAsString = UploadInfoJsonSerializer.serialize(info);
    if (infoAsString == null || infoAsString.isEmpty()) {
      throw new IOException("Failed to serialize UploadInfo to JSON");
    }
    byte[] jsonBytes = infoAsString.getBytes(StandardCharsets.UTF_8);
    BlobClient infoBlob = containerClient.getBlobClient(metadataPrefix + info.getId() + ".info");
    infoBlob.upload(BinaryData.fromBytes(jsonBytes), true);
  }

  /**
   * Validates that the upload has sufficient block budget remaining within Azure's 50,000 blocks
   * limit.
   *
   * <p><b>Why:</b> Azure Block Blobs enforce a strict maximum ceiling of 50,000 blocks per blob. If
   * an upload receives too many small chunks, it risks hitting this limit before finishing. This
   * check runs in O(1) time at the trust boundary to prevent deadlocked uploads.
   *
   * @param upload The current upload metadata
   * @param currentBlockCount Number of already committed blocks
   * @throws MaxAppendSizeExceededException If remaining block capacity is exhausted
   */
  void validateRemainingBlockBudget(UploadInfo upload, int currentBlockCount)
      throws MaxAppendSizeExceededException {
    int remainingBlocks = MAX_BLOCKS_PER_BLOB - currentBlockCount;
    if (remainingBlocks <= 0) {
      throw new MaxAppendSizeExceededException(
          "Azure Block Blob maximum block limit of "
              + MAX_BLOCKS_PER_BLOB
              + " blocks has been reached for upload ID "
              + (upload != null ? upload.getId() : "null"));
    }
  }

  private static class ReadChunkResult {
    final long bytesRead;
    final IOException exception;

    ReadChunkResult(long bytesRead, IOException exception) {
      this.bytesRead = bytesRead;
      this.exception = exception;
    }
  }

  /** Reads up to maxBytes from InputStream into target File, preserving read count on exception. */
  private ReadChunkResult readChunk(InputStream is, File targetFile, long maxBytes) {
    long totalRead = 0L;
    byte[] buffer = new byte[8192];
    IOException readException = null;
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
    } catch (IOException e) {
      readException = e;
    }
    return new ReadChunkResult(totalRead, readException);
  }

  /** Retrieves committed block IDs from Azure Block Blob. */
  private List<String> getCommittedBlockIds(BlockBlobClient blockBlobClient) throws IOException {
    List<String> blockIds = new ArrayList<>();
    try {
      BlockList blockList = blockBlobClient.listBlocks(BlockListType.COMMITTED);
      if (blockList != null && blockList.getCommittedBlocks() != null) {
        for (Block block : blockList.getCommittedBlocks()) {
          blockIds.add(block.getName());
        }
      }
    } catch (BlobStorageException e) {
      if (AzureUtils.parseErrorResponse(e) != AzureErrorType.BLOB_NOT_FOUND) {
        throw new IOException("Failed to retrieve committed block list from Azure Blob", e);
      }
    } catch (Exception e) {
      throw new IOException("Failed to retrieve committed block list from Azure Blob", e);
    }
    return blockIds;
  }

  /** Validates effective max append size limit. */
  void validateMaxAppendSize(long totalAppended, Long effectiveMaxAppendSize)
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
  void validateMinAppendSize(long totalAppended) throws MinAppendSizeNotMetException {
    if (minAppendSize != null && totalAppended < minAppendSize) {
      throw new MinAppendSizeNotMetException(
          "Append size " + totalAppended + " is less than minimum allowed of " + minAppendSize);
    }
  }

  /**
   * Stages local chunk temp file as a Block Blob block without mutating blockIds on the worker
   * thread.
   */
  private void stageBlock(
      BlockBlobClient blockBlobClient, String blockId, File chunkFile, long chunkSize)
      throws IOException {
    if (chunkSize > 0) {
      try (InputStream chunkIs = new java.io.BufferedInputStream(new FileInputStream(chunkFile))) {
        blockBlobClient.stageBlock(blockId, chunkIs, chunkSize);
      }
    }
  }

  /** Commits empty block list for 0-byte upload completion. */
  private void commitEmptyDataBlob(UploadId id) {
    BlockBlobClient blockBlobClient =
        containerClient.getBlobClient(uploadPrefix + id).getBlockBlobClient();
    blockBlobClient.commitBlockList(new ArrayList<>(), true);
  }

  /**
   * Generates a Base64-encoded block ID matching Azure Block Blob standards.
   *
   * <p>Incorporates an 8-character random UUID suffix to guarantee that late-landing blocks from
   * previous aborted or timed-out worker threads never collide with or overwrite blocks in
   *
   * @param index The block index
   * @return The Base64-encoded block ID
   */
  String generateBlockId(int index) {
    String idString =
        String.format("blk-%06d-%s", index, UUID.randomUUID().toString().substring(0, 8));
    return Base64.getEncoder().encodeToString(idString.getBytes(StandardCharsets.UTF_8));
  }

  private boolean checkAndApplyDeduplication(UploadInfo uploadInfo) throws IOException {
    if (!isUploadDeduplicationEnabled()
        || uploadInfo == null
        || uploadInfo.getChecksum() == null
        || uploadInfo.getChecksumAlgorithm() == null) {
      return false;
    }

    try {
      ChecksumKey key = ChecksumKey.of(uploadInfo.getChecksum(), uploadInfo.getChecksumAlgorithm());
      deduplicate(uploadInfo, key);
      return true;
    } catch (Exception e) {
      log.warn(
          "Failed to apply deduplication for upload {}: {}", uploadInfo.getId(), e.getMessage());
      return false;
    }
  }

  /** Deletes local file suppressing exceptions. */
  void deleteFileQuietly(File file) {
    if (file != null && file.exists()) {
      try {
        Files.delete(file.toPath());
      } catch (Exception ignored) {
      }
    }
  }
}
