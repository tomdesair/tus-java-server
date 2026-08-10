package me.desair.tus.server.upload.s3;

import io.minio.ComposeObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SourceObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.concatenation.UploadInputStreamEnumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3-native implementation of {@link UploadConcatenationService} using MinIO Java SDK.
 *
 * <p>Concatenation Strategy for Developers:
 *
 * <ul>
 *   <li><b>Server-Side S3 Object Composition ({@code composeObject})</b>: When all partial upload
 *       parts meet S3's minimum part size constraint ($\ge$ 5 MB), concatenation is executed
 *       entirely on the S3 storage cluster using {@code composeObject}. This avoids downloading any
 *       bytes to the server, enabling instant multi-GB file stitching with zero bandwidth or RAM
 *       overhead.
 *   <li><b>Streaming Re-upload Fallback</b>: If any partial upload is under 5 MB (sub-5MB parts
 *       cannot be composed via S3's native compose API), the service streams bytes sequentially
 *       using {@link SequenceInputStream} and re-uploads the concatenated stream directly to S3.
 * </ul>
 */
public class S3ConcatenationService implements UploadConcatenationService {

  private static final Logger log = LoggerFactory.getLogger(S3ConcatenationService.class);
  private static final long DEFAULT_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MB

  private final MinioClient minioClient;
  private final String bucket;
  private final String objectPrefix;
  private final long minPartSize;
  private final Path temporaryDirectory;
  private UploadStorageService uploadStorageService;

  /**
   * Basic constructor using default object prefix ("uploads/") and Java temp directory.
   *
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket name
   */
  public S3ConcatenationService(MinioClient minioClient, String bucket) {
    this(minioClient, bucket, "uploads/", null, null);
  }

  /**
   * Convenient constructor taking MinioClient, bucket, and UploadStorageService.
   *
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket name
   * @param uploadStorageService Underlying storage service
   */
  public S3ConcatenationService(
      MinioClient minioClient, String bucket, UploadStorageService uploadStorageService) {
    this(minioClient, bucket, "uploads/", uploadStorageService, null);
  }

  /**
   * Constructs an S3ConcatenationService.
   *
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket name
   * @param objectPrefix Key prefix for data objects
   * @param uploadStorageService Underlying storage service
   * @param temporaryDirectory Directory for temporary buffer files
   */
  public S3ConcatenationService(
      MinioClient minioClient,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory) {
    this(
        minioClient,
        bucket,
        objectPrefix,
        uploadStorageService,
        temporaryDirectory,
        DEFAULT_MIN_PART_SIZE);
  }

  /** Full constructor allowing custom minimum part size. */
  public S3ConcatenationService(
      MinioClient minioClient,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory,
      long minPartSize) {
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.objectPrefix = objectPrefix != null ? objectPrefix : "";
    this.uploadStorageService = uploadStorageService;
    this.temporaryDirectory =
        temporaryDirectory != null
            ? temporaryDirectory
            : java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
    this.minPartSize = minPartSize;
  }

  public void setUploadStorageService(UploadStorageService uploadStorageService) {
    this.uploadStorageService = uploadStorageService;
  }

  @Override
  public void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo == null
        || !uploadInfo.isUploadInProgress()
        || uploadInfo.getConcatenationPartIds() == null) {
      return;
    }

    Long expirationPeriod =
        uploadStorageService != null ? uploadStorageService.getUploadExpirationPeriod() : null;
    List<UploadInfo> partialUploads = getPartialUploads(uploadInfo);

    Long totalLength = calculateTotalLength(partialUploads);
    boolean completed = checkAllCompleted(expirationPeriod, partialUploads);

    if (totalLength != null && totalLength > 0 && completed) {
      // S3 Constraint Check: Server-side composeObject requires all source parts to be >= 5 MB
      boolean canUseServerSideCopy =
          partialUploads.stream()
              .allMatch(p -> p.getLength() != null && p.getLength() >= minPartSize);

      String targetObjectKey = buildObjectKey(uploadInfo.getId());

      if (canUseServerSideCopy) {
        // Fast path: Compose S3 objects on cluster server-side without downloading data
        mergeUsingServerSideCopy(targetObjectKey, partialUploads);
      } else {
        // Fallback path: Sequential stream re-upload for sub-5MB parts
        mergeUsingStreamingReupload(targetObjectKey, partialUploads, totalLength);
      }

      uploadInfo.setLength(totalLength);
      uploadInfo.setOffset(totalLength);
      if (expirationPeriod != null) {
        uploadInfo.updateExpiration(expirationPeriod);
      }
      uploadInfo.setStorageUploadId(targetObjectKey);

      if (uploadStorageService != null) {
        try {
          uploadStorageService.update(uploadInfo);
        } catch (UploadNotFoundException e) {
          log.warn("Failed to update concatenated upload info for " + uploadInfo.getId(), e);
        }
      }
    }
  }

  @Override
  public InputStream getConcatenatedBytes(UploadInfo uploadInfo)
      throws IOException, UploadNotFoundException {

    if (uploadInfo == null) {
      return null;
    }

    if (uploadInfo.getStorageUploadId() == null) {
      merge(uploadInfo);
    }

    if (uploadStorageService != null) {
      return uploadStorageService.getUploadedBytes(uploadInfo.getId());
    }

    throw new IOException(
        "UploadStorageService must be configured to retrieve concatenated upload bytes");
  }

  @Override
  public List<UploadInfo> getPartialUploads(UploadInfo info)
      throws IOException, UploadNotFoundException {
    List<String> concatenationParts = info.getConcatenationPartIds();

    if (concatenationParts == null || concatenationParts.isEmpty()) {
      return Collections.emptyList();
    }

    List<UploadInfo> output = new ArrayList<>(concatenationParts.size());
    for (String childUri : concatenationParts) {
      UploadInfo childInfo =
          uploadStorageService != null
              ? uploadStorageService.getUploadInfo(childUri, info.getOwnerKey())
              : null;
      if (childInfo == null) {
        throw new UploadNotFoundException(
            "Upload with URI " + childUri + " was not found for owner " + info.getOwnerKey());
      }

      // Ensure only uploads with the same owner key can be merged (either equal or both null)
      if (!Objects.equals(childInfo.getOwnerKey(), info.getOwnerKey())) {
        log.warn(
            "Owner key mismatch during S3 concatenation merge check. Parent upload ID {} has owner key '{}', "
                + "but partial child upload ID {} has owner key '{}'. Merging disallowed.",
            info.getId(),
            info.getOwnerKey(),
            childInfo.getId(),
            childInfo.getOwnerKey());
        throw new UploadNotFoundException(
            "Upload with URI " + childUri + " has a mismatching owner key");
      }
      output.add(childInfo);
    }
    return output;
  }

  private void mergeUsingServerSideCopy(String targetKey, List<UploadInfo> partialUploads)
      throws IOException {
    try {
      List<SourceObject> sources = new ArrayList<>();
      for (UploadInfo partial : partialUploads) {
        // Restore partKey fallback logic:
        // In unit tests or mock storage environments, storageUploadId may be null.
        // Falling back to buildObjectKey(partial.getId()) ensures we can still resolve
        // the S3 object key for the part, maintaining test compatibility and robustness.
        String partKey =
            partial.getStorageUploadId() != null
                ? partial.getStorageUploadId()
                : buildObjectKey(partial.getId());
        sources.add(SourceObject.builder().bucket(bucket).object(partKey).build());
      }

      // Execute S3 server-side object composition
      minioClient.composeObject(
          ComposeObjectArgs.builder().bucket(bucket).object(targetKey).sources(sources).build());
    } catch (Exception e) {
      throw new IOException("Failed server-side S3 composeObject merge for key " + targetKey, e);
    }
  }

  private void mergeUsingStreamingReupload(
      String targetKey, List<UploadInfo> partialUploads, long totalLength) throws IOException {
    try {
      InputStream combinedStream =
          new SequenceInputStream(
              new UploadInputStreamEnumeration(partialUploads, uploadStorageService));

      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(targetKey).stream(
                  combinedStream, totalLength, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed streaming re-upload merge for key " + targetKey, e);
    }
  }

  private Long calculateTotalLength(List<UploadInfo> partialUploads) {
    Long totalLength = 0L;
    for (UploadInfo childInfo : partialUploads) {
      if (childInfo.getLength() == null) {
        return null;
      }
      totalLength += childInfo.getLength();
    }
    return totalLength;
  }

  private boolean checkAllCompleted(Long expirationPeriod, List<UploadInfo> partialUploads)
      throws IOException {
    boolean completed = true;
    for (UploadInfo childInfo : partialUploads) {
      if (childInfo.isUploadInProgress()) {
        completed = false;
      } else if (expirationPeriod != null) {
        childInfo.updateExpiration(expirationPeriod);
        if (uploadStorageService != null) {
          try {
            uploadStorageService.update(childInfo);
          } catch (UploadNotFoundException e) {
            log.debug("Failed to update child upload expiration for " + childInfo.getId(), e);
          }
        }
      }
    }
    return completed;
  }

  private String buildObjectKey(UploadId id) {
    return objectPrefix + id.toString();
  }
}
