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
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.upload.concatenation.UploadInputStreamEnumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3-native implementation of {@link UploadConcatenationService} using MinIO Java SDK. Uses
 * server-side S3 object composition ({@code composeObject}) when all partial uploads meet S3's
 * minimum part size constraint ($\ge$ 5 MB), and streams via {@link SequenceInputStream} to
 * re-upload to S3 as a fallback when smaller partial uploads are present.
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
   * Basic constructor using default object prefix ("tus-uploads/") and Java temp directory.
   *
   * @param minioClient The MinIO client
   * @param bucket The S3 bucket name
   */
  public S3ConcatenationService(MinioClient minioClient, String bucket) {
    this(minioClient, bucket, "tus-uploads/", null, null);
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
    this(minioClient, bucket, "tus-uploads/", uploadStorageService, null);
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
      boolean canUseServerSideCopy =
          partialUploads.stream()
              .allMatch(p -> p.getLength() != null && p.getLength() >= minPartSize);

      String targetObjectKey = buildObjectKey(uploadInfo.getId().toString());

      if (canUseServerSideCopy) {
        mergeUsingServerSideCopy(targetObjectKey, partialUploads);
      } else {
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
      output.add(childInfo);
    }
    return output;
  }

  private void mergeUsingServerSideCopy(String targetKey, List<UploadInfo> partialUploads)
      throws IOException {
    try {
      List<SourceObject> sources = new ArrayList<>();
      for (UploadInfo partial : partialUploads) {
        String partKey =
            partial.getStorageUploadId() != null
                ? partial.getStorageUploadId()
                : buildObjectKey(partial.getId().toString());
        sources.add(SourceObject.builder().bucket(bucket).object(partKey).build());
      }

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

  private String buildObjectKey(String id) {
    return objectPrefix + id;
  }
}
