package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.specialized.BlockBlobClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side zero-copy {@link UploadConcatenationService} implementation for Azure Blob Storage.
 *
 * <p><b>Azure Concatenation Architecture:</b>
 *
 * <ul>
 *   <li><b>Zero-Copy URL Block Staging ({@code stageBlockFromUrl})</b>: Merges partial upload blobs
 *       into a final concatenated upload directly on the Azure Storage cluster using native
 *       block-copying by reference. Data is copied server-side on Azure without transferring
 *       payload bytes through application memory or network interfaces.
 *   <li><b>Fallback for Local Emulators</b>: If {@code stageBlockFromUrl} returns 501 /
 *       APINotImplemented (e.g., when testing against local Azurite emulator), falls back to
 *       streamed block staging ({@code stageBlock}).
 *   <li><b>Atomic Commit</b>: Commits the composed list of block IDs atomically using {@code
 *       commitBlockList}.
 * </ul>
 */
public class AzureBlobConcatenationService implements UploadConcatenationService {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobConcatenationService.class);

  private final BlobContainerClient containerClient;
  private final String uploadPrefix;
  private final UploadStorageService storageService;

  /**
   * Constructs an {@link AzureBlobConcatenationService} with default upload prefix.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   * @param storageService Backing {@link UploadStorageService} instance
   */
  public AzureBlobConcatenationService(
      BlobContainerClient containerClient, UploadStorageService storageService) {
    this(containerClient, AzureBlobStorageService.DEFAULT_OBJECT_PREFIX, storageService);
  }

  /**
   * Constructs an {@link AzureBlobConcatenationService} with custom upload prefix.
   *
   * @param containerClient Pre-configured Azure {@link BlobContainerClient}
   * @param uploadPrefix Key prefix for data blobs
   * @param storageService Backing {@link UploadStorageService} instance
   */
  public AzureBlobConcatenationService(
      BlobContainerClient containerClient,
      String uploadPrefix,
      UploadStorageService storageService) {
    this.containerClient =
        Objects.requireNonNull(containerClient, "containerClient must not be null");
    this.uploadPrefix = sanitizePrefix(uploadPrefix);
    this.storageService = Objects.requireNonNull(storageService, "storageService must not be null");
  }

  @Override
  public void merge(UploadInfo finalUpload) throws IOException, UploadNotFoundException {
    if (finalUpload == null
        || !finalUpload.isUploadInProgress()
        || finalUpload.getConcatenationPartIds() == null) {
      return;
    }

    Long expirationPeriod =
        storageService != null ? storageService.getUploadExpirationPeriod() : null;
    List<UploadInfo> partialUploads = getPartialUploads(finalUpload);

    Long totalLength = calculateTotalLength(partialUploads);
    boolean completed = checkAllCompleted(expirationPeriod, partialUploads);

    if (totalLength != null && totalLength > 0 && completed) {
      List<String> blockIds = new ArrayList<>();
      BlockBlobClient finalBlockBlob =
          containerClient.getBlobClient(uploadPrefix + finalUpload.getId()).getBlockBlobClient();

      int sequence = 0;
      for (UploadInfo partialInfo : partialUploads) {
        String blockId = generateBlockId(sequence++);
        BlobClient partialBlob = containerClient.getBlobClient(uploadPrefix + partialInfo.getId());

        try {
          // 1. Attempt zero-copy server-side block copying on Azure Storage cluster
          finalBlockBlob.stageBlockFromUrl(blockId, partialBlob.getBlobUrl(), null);
        } catch (BlobStorageException e) {
          // 2. Fallback to stream staging if stageBlockFromUrl is not implemented by emulator
          AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
          if (errorType == AzureErrorType.API_NOT_IMPLEMENTED || e.getStatusCode() == 400) {
            try (InputStream partIs = storageService.getUploadedBytes(partialInfo.getId())) {
              finalBlockBlob.stageBlock(blockId, partIs, partialInfo.getOffset());
            }
          } else {
            throw e;
          }
        }
        blockIds.add(blockId);
      }

      // 3. Atomically commit block list on Azure Storage
      finalBlockBlob.commitBlockList(blockIds, true);

      // Clean up any sub-threshold .part blob created during upload instantiation
      try {
        containerClient
            .getBlobClient(uploadPrefix + finalUpload.getId() + ".part")
            .deleteIfExists();
      } catch (Exception ignored) {
      }

      // 4. Update final upload attributes
      finalUpload.setOffset(totalLength);
      finalUpload.setLength(totalLength);
      finalUpload.setStorageUploadId(uploadPrefix + finalUpload.getId());
      if (expirationPeriod != null) {
        finalUpload.updateExpiration(expirationPeriod);
      }
      storageService.update(finalUpload);

      log.info(
          "Successfully merged {} partial uploads into concatenated upload {}",
          partialUploads.size(),
          finalUpload.getId());
    }
  }

  @Override
  public InputStream getConcatenatedBytes(UploadInfo info)
      throws IOException, UploadNotFoundException {
    if (info == null) {
      throw new UploadNotFoundException("UploadInfo must not be null");
    }

    if (info.isUploadInProgress()) {
      merge(info);
    }

    if (!info.isUploadInProgress() && storageService != null) {
      return storageService.getUploadedBytes(info.getId());
    }

    return new ByteArrayInputStream(new byte[0]);
  }

  @Override
  public List<UploadInfo> getPartialUploads(UploadInfo info)
      throws IOException, UploadNotFoundException {
    if (info == null || info.getConcatenationPartIds() == null) {
      return Collections.emptyList();
    }

    List<UploadInfo> result = new ArrayList<>();
    for (String partUri : info.getConcatenationPartIds()) {
      UploadInfo partInfo = storageService.getUploadInfo(partUri, info.getOwnerKey());
      if (partInfo == null) {
        throw new UploadNotFoundException(
            "Partial upload with URI " + partUri + " not found for concatenated upload");
      }
      result.add(partInfo);
    }
    return result;
  }

  private Long calculateTotalLength(List<UploadInfo> partialUploads) {
    if (partialUploads == null || partialUploads.isEmpty()) {
      return null;
    }
    long total = 0L;
    for (UploadInfo info : partialUploads) {
      if (info == null || info.getLength() == null) {
        return null;
      }
      total += info.getLength();
    }
    return total;
  }

  private boolean checkAllCompleted(Long expirationPeriod, List<UploadInfo> partialUploads) {
    if (partialUploads == null || partialUploads.isEmpty()) {
      return false;
    }
    for (UploadInfo info : partialUploads) {
      if (info == null || info.isUploadInProgress() || info.isExpired()) {
        return false;
      }
    }
    return true;
  }

  private String generateBlockId(int index) {
    String idString = String.format("concat-%06d", index);
    return Base64.getEncoder().encodeToString(idString.getBytes(StandardCharsets.UTF_8));
  }

  private String sanitizePrefix(String prefix) {
    if (prefix == null || prefix.isEmpty()) {
      return "";
    }
    String result = prefix.startsWith("/") ? prefix.substring(1) : prefix;
    return result.endsWith("/") ? result : result + "/";
  }
}
