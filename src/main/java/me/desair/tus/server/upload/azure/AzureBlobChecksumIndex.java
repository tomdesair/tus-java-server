package me.desair.tus.server.upload.azure;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.checksum.ChecksumIndex;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Azure Blob Storage implementation of {@link ChecksumIndex}.
 *
 * <p>Storage layout:
 *
 * <ul>
 *   <li>{@code <checksumsPrefix>/<algorithm>/<hexChecksum>/parent} - contains parent upload ID
 *   <li>{@code <checksumsPrefix>/<algorithm>/<hexChecksum>/parent-deleted} - tombstone marker
 *   <li>{@code <checksumsPrefix>/<algorithm>/<hexChecksum>/children/<childId>} - child upload
 *       markers
 * </ul>
 */
public class AzureBlobChecksumIndex implements ChecksumIndex {

  private static final Logger log = LoggerFactory.getLogger(AzureBlobChecksumIndex.class);

  private static final String DEFAULT_CHECKSUMS_PREFIX = "checksums/";
  private static final String PARENT_OBJECT = "parent";
  private static final String PARENT_DELETED_OBJECT = "parent-deleted";
  private static final String CHILDREN_PREFIX = "children/";

  private final BlobContainerClient containerClient;
  private final String checksumsPrefix;

  /**
   * Constructs a new {@link AzureBlobChecksumIndex}.
   *
   * @param containerClient the Azure BlobContainerClient
   */
  public AzureBlobChecksumIndex(BlobContainerClient containerClient) {
    this(containerClient, DEFAULT_CHECKSUMS_PREFIX);
  }

  /**
   * Constructs a new {@link AzureBlobChecksumIndex} with a custom checksums prefix.
   *
   * @param containerClient the Azure BlobContainerClient
   * @param checksumsPrefix the prefix path for checksums
   */
  public AzureBlobChecksumIndex(BlobContainerClient containerClient, String checksumsPrefix) {
    this.containerClient =
        Objects.requireNonNull(containerClient, "containerClient must not be null");
    this.checksumsPrefix = Utils.sanitizePrefix(checksumsPrefix);
  }

  @Override
  public boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(uploadId, "uploadId must not be null");

    String parentKey = buildKey(key, PARENT_OBJECT);
    BlobClient parentBlob = containerClient.getBlobClient(parentKey);
    byte[] content = uploadId.toString().getBytes(StandardCharsets.UTF_8);

    // Atomic CAS write using Azure BlobRequestConditions.setIfNoneMatch("*")
    try {
      BlobRequestConditions conditions = new BlobRequestConditions().setIfNoneMatch("*");
      BlobParallelUploadOptions options =
          new BlobParallelUploadOptions(BinaryData.fromBytes(content))
              .setRequestConditions(conditions);
      parentBlob.uploadWithResponse(options, null, null);
      return true;
    } catch (BlobStorageException e) {
      AzureErrorType errorType = AzureUtils.parseErrorResponse(e);
      if (errorType == AzureErrorType.CONFLICT || errorType == AzureErrorType.PRECONDITION_FAILED) {
        return false;
      }
      throw new IOException("Failed to create parent index blob in Azure: " + parentKey, e);
    }
  }

  @Override
  public UploadId findParent(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String parentKey = buildKey(key, PARENT_OBJECT);
    BlobClient parentBlob = containerClient.getBlobClient(parentKey);

    try {
      byte[] bytes = parentBlob.downloadContent().toBytes();
      String idStr = new String(bytes, StandardCharsets.UTF_8).trim();
      return idStr.isEmpty() ? null : new UploadId(idStr);
    } catch (BlobStorageException e) {
      if (AzureUtils.parseErrorResponse(e) == AzureErrorType.BLOB_NOT_FOUND) {
        return null;
      }
      throw new IOException("Failed to read parent index blob in Azure: " + parentKey, e);
    }
  }

  @Override
  public void markParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String parentDeletedKey = buildKey(key, PARENT_DELETED_OBJECT);

    // Write empty tombstone marker while keeping parent pointer blob intact
    // until deleteIndex(key) is invoked when the last child terminates.
    BlobClient tombstoneBlob = containerClient.getBlobClient(parentDeletedKey);
    tombstoneBlob.upload(BinaryData.fromBytes(new byte[0]), true);
  }

  @Override
  public boolean isParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    return AzureUtils.blobExists(containerClient, buildKey(key, PARENT_DELETED_OBJECT));
  }

  @Override
  public void addChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");
    String childKey = buildKey(key, CHILDREN_PREFIX + childId);
    BlobClient childBlob = containerClient.getBlobClient(childKey);
    childBlob.upload(BinaryData.fromBytes(new byte[0]), true);
  }

  @Override
  public void removeChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");
    String childKey = buildKey(key, CHILDREN_PREFIX + childId);
    AzureUtils.deleteBlobQuietly(containerClient, childKey);
  }

  @Override
  public boolean hasChildren(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String prefix = buildKey(key, CHILDREN_PREFIX);

    try {
      ListBlobsOptions options = new ListBlobsOptions().setPrefix(prefix).setMaxResultsPerPage(1);
      return containerClient.listBlobs(options, null).iterator().hasNext();
    } catch (Exception e) {
      throw new IOException("Failed to list children blobs in Azure for prefix " + prefix, e);
    }
  }

  @Override
  public void deleteIndex(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String prefix = buildKey(key, "");

    try {
      ListBlobsOptions options = new ListBlobsOptions().setPrefix(prefix);
      for (BlobItem item : containerClient.listBlobs(options, null)) {
        AzureUtils.deleteBlobQuietly(containerClient, item.getName());
      }
    } catch (Exception e) {
      throw new IOException(
          "Failed to delete checksum index blobs in Azure for prefix " + prefix, e);
    }
  }

  @Override
  public Set<ChecksumKey> getAllIndexedKeys() throws IOException {
    Set<ChecksumKey> keys = new LinkedHashSet<>();
    try {
      ListBlobsOptions options = new ListBlobsOptions().setPrefix(checksumsPrefix);
      for (BlobItem item : containerClient.listBlobs(options, null)) {
        if (item == null || item.getName() == null) {
          continue;
        }
        String blobName = item.getName();
        if (blobName.endsWith("/" + PARENT_OBJECT)) {
          String relative = blobName.substring(checksumsPrefix.length());
          ChecksumKey key = ChecksumKey.parseFromIndexRelativePath(relative, PARENT_OBJECT);
          if (key != null) {
            keys.add(key);
          }
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to list indexed checksum keys from Azure Blob Storage", e);
    }
    return keys;
  }

  private String buildKey(ChecksumKey key, String subPath) {
    return checksumsPrefix + key + (subPath.isEmpty() ? "" : "/" + subPath);
  }
}
