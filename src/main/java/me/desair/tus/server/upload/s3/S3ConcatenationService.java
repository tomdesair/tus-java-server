package me.desair.tus.server.upload.s3;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.AbstractConcatenationService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.concatenation.UploadInputStreamEnumeration;
import me.desair.tus.server.util.Utils;
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
public class S3ConcatenationService extends AbstractConcatenationService {

  private static final Logger log = LoggerFactory.getLogger(S3ConcatenationService.class);
  private static final long DEFAULT_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MB

  private final MinioClient minioClient;
  private final String bucket;
  private final String objectPrefix;
  private final long minPartSize;
  private final Path temporaryDirectory;
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
   * Package-private convenience constructor taking pre-configured {@link MinioClient}.
   *
   * @param minioClient The MinIO client
   * @param bucket S3 bucket name
   * @param uploadStorageService Underlying storage service
   */
  S3ConcatenationService(
      MinioClient minioClient, String bucket, UploadStorageService uploadStorageService) {
    this(
        minioClient,
        bucket,
        "uploads/",
        uploadStorageService,
        null,
        DEFAULT_MIN_PART_SIZE,
        new S3ServerSideComposeHelper(minioClient));
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
    super(uploadStorageService);
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.objectPrefix = Utils.sanitizePrefix(objectPrefix);
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
    return S3Utils.buildMinioClient(endpoint, region, accessKey, secretKey);
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
      boolean canUseServerSideCopy =
          s3ComposeHelper.canComposePartialUploads(partialUploads, minPartSize);

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

  private void mergeUsingServerSideCopy(
      String targetKey, List<UploadInfo> partialUploads, long totalLength) throws IOException {
    try {
      List<String> partKeys = new ArrayList<>();
      for (UploadInfo partial : partialUploads) {
        // Resolve duplicate child uploads dynamically to the parent upload's physical S3 object
        // key to prevent composing against non-existent child blob targets.
        String key;
        if (uploadStorageService instanceof S3StorageService) {
          key = ((S3StorageService) uploadStorageService).getS3ObjectKey(partial);
        } else if (partial.getDuplicatesUploadId() != null) {
          key = objectPrefix + partial.getDuplicatesUploadId();
        } else if (partial.getStorageUploadId() != null) {
          key = partial.getStorageUploadId();
        } else {
          key = objectPrefix + partial.getId();
        }
        partKeys.add(key);
      }

      // Execute S3 server-side object composition via native S3 multipart copy helper.
      // S3ServerSideComposeHelper executes an UploadPartCopy sequence without Content-MD5,
      // avoiding MinIO SDK's internal EMPTY_BODY issue on Amazon AWS S3.
      s3ComposeHelper.compose(bucket, targetKey, partKeys);
    } catch (Exception e) {
      // If server-side composition fails for any reason (e.g. S3 permissions, regional policy,
      // or unhandled multipart copy restriction), mark compose as unsupported on this helper
      // and fall back to streaming re-upload as a safety net.
      // This ensures concatenated uploads always succeed even if server-side copy is denied.
      s3ComposeHelper.setComposeSupported(false);
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

  @Override
  public void close() throws IOException {
    if (s3ComposeHelper != null) {
      s3ComposeHelper.close();
    }
  }

  private String buildObjectKey(UploadId id) {
    return objectPrefix + id.toString();
  }
}
