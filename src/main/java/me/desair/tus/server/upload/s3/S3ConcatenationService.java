package me.desair.tus.server.upload.s3;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
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
  private final UploadStorageService uploadStorageService;
  private S3ServerSideComposeHelper s3ComposeHelper;

  /**
   * Convenience constructor for local S3-compatible backends taking backing {@link
   * UploadStorageService}. Defaults the region to "local".
   *
   * @param endpoint S3 endpoint URL (e.g. "http://localhost:9000")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   * @param uploadStorageService Underlying storage service
   */
  public S3ConcatenationService(
      String endpoint,
      String accessKey,
      String secretKey,
      String bucket,
      UploadStorageService uploadStorageService) {
    this(endpoint, "local", accessKey, secretKey, bucket, uploadStorageService);
  }

  /**
   * Convenient constructor taking connection parameters and backing {@link UploadStorageService}.
   *
   * @param endpoint S3 endpoint URL (e.g. "https://s3.amazonaws.com" or "http://localhost:9000")
   * @param region S3 region name (e.g. "us-east-1", "eu-central-1")
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   * @param uploadStorageService Underlying storage service
   */
  public S3ConcatenationService(
      String endpoint,
      String region,
      String accessKey,
      String secretKey,
      String bucket,
      UploadStorageService uploadStorageService) {
    this(
        endpoint,
        region,
        accessKey,
        secretKey,
        bucket,
        "uploads/",
        uploadStorageService,
        null,
        DEFAULT_MIN_PART_SIZE);
  }

  /**
   * Full constructor allowing custom object prefix, temporary directory, and minimum part size.
   *
   * <p>Delegates to the internal package-private constructor accepting {@link MinioClient}.
   *
   * @param endpoint S3 endpoint URL
   * @param region S3 region name
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @param bucket S3 bucket name
   * @param objectPrefix Key prefix for data objects
   * @param uploadStorageService Underlying storage service
   * @param temporaryDirectory Directory for temporary buffer files
   * @param minPartSize Minimum part chunk size for server-side composition
   */
  public S3ConcatenationService(
      String endpoint,
      String region,
      String accessKey,
      String secretKey,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory,
      long minPartSize) {
    this(
        buildMinioClient(endpoint, region, accessKey, secretKey),
        bucket,
        objectPrefix,
        uploadStorageService,
        temporaryDirectory,
        minPartSize,
        new S3ServerSideComposeHelper(endpoint, region, accessKey, secretKey));
  }

  /**
   * Internal package-private constructor accepting {@link MinioClient} where all parameter
   * configuration is concentrated.
   */
  S3ConcatenationService(
      MinioClient minioClient,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory,
      long minPartSize,
      S3ServerSideComposeHelper s3ComposeHelper) {
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.objectPrefix = objectPrefix != null ? objectPrefix : "";
    this.uploadStorageService =
        Objects.requireNonNull(uploadStorageService, "UploadStorageService must not be null");
    this.temporaryDirectory =
        temporaryDirectory != null
            ? temporaryDirectory
            : java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"));
    this.minPartSize = minPartSize;
    this.s3ComposeHelper =
        s3ComposeHelper != null ? s3ComposeHelper : new S3ServerSideComposeHelper(this.minioClient);
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

  @Override
  public void merge(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {
    if (uploadInfo == null
        || !uploadInfo.isUploadInProgress()
        || uploadInfo.getConcatenationPartIds() == null) {
      return;
    }

    Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();
    List<UploadInfo> partialUploads = getPartialUploads(uploadInfo);

    Long totalLength = calculateTotalLength(partialUploads);
    boolean completed = checkAllCompleted(expirationPeriod, partialUploads);

    if (totalLength != null && totalLength > 0 && completed) {
      // AWS S3 composeObject / multipart upload constraints:
      // 1. Single part: must be >= minPartSize (5MB) and have valid storageUploadId to compose.
      // 2. Multiple parts (>= 2): all parts EXCEPT the final part must be >= minPartSize (5MB).
      //    The final part is allowed to be < 5MB while still using zero-copy server-side
      //    composition.
      boolean canUseServerSideCopy = !partialUploads.isEmpty();
      if (partialUploads.size() == 1) {
        UploadInfo p = partialUploads.get(0);
        canUseServerSideCopy =
            p.getLength() != null && p.getLength() >= minPartSize && p.getStorageUploadId() != null;
      } else {
        for (int i = 0; i < partialUploads.size(); i++) {
          UploadInfo p = partialUploads.get(i);
          if (p.getLength() == null || p.getStorageUploadId() == null) {
            canUseServerSideCopy = false;
            break;
          }
          if (i < partialUploads.size() - 1 && p.getLength() < minPartSize) {
            canUseServerSideCopy = false;
            break;
          }
        }
      }

      String targetObjectKey = buildObjectKey(uploadInfo.getId());

      if (canUseServerSideCopy) {
        // Fast path: Compose S3 objects on cluster server-side without downloading data
        mergeUsingServerSideCopy(targetObjectKey, partialUploads, totalLength);
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

      try {
        uploadStorageService.update(uploadInfo);
      } catch (UploadNotFoundException e) {
        log.warn("Failed to update concatenated upload info for " + uploadInfo.getId(), e);
      }
    }
  }

  @Override
  public InputStream getConcatenatedBytes(UploadInfo uploadInfo)
      throws IOException, UploadNotFoundException {
    if (uploadInfo == null) {
      return null;
    }

    if (uploadInfo.isUploadInProgress()) {
      merge(uploadInfo);
    }

    if (!uploadInfo.isUploadInProgress()) {
      return uploadStorageService.getUploadedBytes(uploadInfo.getId());
    }

    return new java.io.ByteArrayInputStream(new byte[0]);
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
      UploadInfo childInfo = uploadStorageService.getUploadInfo(childUri, info.getOwnerKey());
      if (childInfo == null) {
        throw new UploadNotFoundException(
            "Upload with URI " + childUri + " was not found for owner " + info.getOwnerKey());
      }

      // Ensure only uploads with the same owner key can be merged (either equal or both null)
      if (!Objects.equals(childInfo.getOwnerKey(), info.getOwnerKey())) {
        log.warn(
            "Owner key mismatch during S3 concatenation merge check. Parent upload ID {} has owner"
                + " key '{}', but partial child upload ID {} has owner key '{}'. Merging"
                + " disallowed.",
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

  private void mergeUsingServerSideCopy(
      String targetKey, List<UploadInfo> partialUploads, long totalLength) throws IOException {
    try {
      List<String> partKeys = new ArrayList<>();
      for (UploadInfo partial : partialUploads) {
        partKeys.add(partial.getStorageUploadId());
      }

      // Execute S3 server-side object composition via native S3 multipart copy helper.
      // S3ServerSideComposeHelper executes an UploadPartCopy sequence without Content-MD5,
      // avoiding MinIO SDK's internal EMPTY_BODY issue on Amazon AWS S3.
      s3ComposeHelper.compose(bucket, targetKey, partKeys);
    } catch (Exception e) {
      // If server-side composition fails for any reason (e.g. S3 permissions, regional policy,
      // or unhandled multipart copy restriction), fall back to streaming re-upload as a safety net.
      // This ensures concatenated uploads always succeed even if server-side copy is denied.
      log.warn(
          "Server-side S3 composition failed for target key {}; falling back to streaming re-upload: {}",
          targetKey,
          e.getMessage());
      mergeUsingStreamingReupload(targetKey, partialUploads, totalLength);
    }
  }

  void setS3ServerSideComposeHelper(S3ServerSideComposeHelper s3ComposeHelper) {
    this.s3ComposeHelper = s3ComposeHelper;
  }

  private void mergeUsingStreamingReupload(
      String targetKey, List<UploadInfo> partialUploads, long totalLength) throws IOException {
    try (InputStream combinedStream =
        new SequenceInputStream(
            new UploadInputStreamEnumeration(partialUploads, uploadStorageService))) {

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
        try {
          uploadStorageService.update(childInfo);
        } catch (UploadNotFoundException e) {
          log.debug("Failed to update child upload expiration for " + childInfo.getId(), e);
        }
      }
    }
    return completed;
  }

  private String buildObjectKey(UploadId id) {
    return objectPrefix + id.toString();
  }
}
