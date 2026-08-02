package me.desair.tus.server.upload.s3;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.UploadPartCopyRequest;
import software.amazon.awssdk.services.s3.model.UploadPartCopyResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * S3-native implementation of {@link UploadConcatenationService}. Uses server-side S3 {@code
 * UploadPartCopy} when all partial uploads meet S3's minimum part size constraint ($\ge$ 5 MB), and
 * streams via {@link SequenceInputStream} to re-upload to S3 as a fallback when smaller partial
 * uploads are present.
 */
public class S3ConcatenationService implements UploadConcatenationService {

  private static final Logger log = LoggerFactory.getLogger(S3ConcatenationService.class);
  private static final long DEFAULT_MIN_PART_SIZE = 5L * 1024 * 1024; // 5 MB

  private final S3Client s3Client;
  private final String bucket;
  private final String objectPrefix;
  private final long minPartSize;
  private final Path temporaryDirectory;
  private UploadStorageService uploadStorageService;

  /**
   * Basic constructor using default object prefix ("tus-uploads/") and Java temp directory.
   *
   * @param s3Client The S3 client
   * @param bucket The S3 bucket name
   */
  public S3ConcatenationService(S3Client s3Client, String bucket) {
    this(s3Client, bucket, "tus-uploads/", null, null);
  }

  /**
   * Convenient constructor taking S3Client, bucket, and UploadStorageService.
   *
   * @param s3Client The S3 client
   * @param bucket The S3 bucket name
   * @param uploadStorageService Underlying storage service
   */
  public S3ConcatenationService(
      S3Client s3Client, String bucket, UploadStorageService uploadStorageService) {
    this(s3Client, bucket, "tus-uploads/", uploadStorageService, null);
  }

  /**
   * Constructs an S3ConcatenationService.
   *
   * @param s3Client The S3 client
   * @param bucket The S3 bucket name
   * @param objectPrefix Key prefix for data objects
   * @param uploadStorageService Underlying storage service
   * @param temporaryDirectory Directory for temporary buffer files
   */
  public S3ConcatenationService(
      S3Client s3Client,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory) {
    this(
        s3Client,
        bucket,
        objectPrefix,
        uploadStorageService,
        temporaryDirectory,
        DEFAULT_MIN_PART_SIZE);
  }

  /** Full constructor allowing custom minimum part size. */
  public S3ConcatenationService(
      S3Client s3Client,
      String bucket,
      String objectPrefix,
      UploadStorageService uploadStorageService,
      Path temporaryDirectory,
      long minPartSize) {
    this.s3Client = Objects.requireNonNull(s3Client, "S3Client must not be null");
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
      String multipartUploadId;

      if (canUseServerSideCopy) {
        multipartUploadId = mergeUsingServerSideCopy(targetObjectKey, partialUploads);
      } else {
        multipartUploadId = mergeUsingStreamingReupload(targetObjectKey, partialUploads);
      }

      uploadInfo.setLength(totalLength);
      uploadInfo.setOffset(totalLength);
      if (expirationPeriod != null) {
        uploadInfo.updateExpiration(expirationPeriod);
      }
      uploadInfo.setStorageUploadId(multipartUploadId);

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

    String objectKey = buildObjectKey(uploadInfo.getId().toString());
    try {
      return s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (NoSuchKeyException e) {
      throw new UploadNotFoundException(
          "Uploaded concatenated object not found for ID " + uploadInfo.getId());
    }
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

  private String mergeUsingServerSideCopy(String targetKey, List<UploadInfo> partialUploads)
      throws IOException {
    CreateMultipartUploadResponse createResponse =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(bucket).key(targetKey).build());
    String uploadId = createResponse.uploadId();

    List<CompletedPart> completedParts = new ArrayList<>();
    int partNumber = 1;

    try {
      for (UploadInfo partial : partialUploads) {
        String sourceKey = buildObjectKey(partial.getId().toString());

        UploadPartCopyResponse copyResponse =
            s3Client.uploadPartCopy(
                UploadPartCopyRequest.builder()
                    .destinationBucket(bucket)
                    .destinationKey(targetKey)
                    .sourceBucket(bucket)
                    .sourceKey(sourceKey)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build());

        completedParts.add(
            CompletedPart.builder()
                .partNumber(partNumber)
                .eTag(copyResponse.copyPartResult().eTag())
                .build());
        partNumber++;
      }

      s3Client.completeMultipartUpload(
          software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(targetKey)
              .uploadId(uploadId)
              .multipartUpload(
                  software.amazon.awssdk.services.s3.model.CompletedMultipartUpload.builder()
                      .parts(completedParts)
                      .build())
              .build());
      return uploadId;
    } catch (Exception e) {
      s3Client.abortMultipartUpload(
          software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(targetKey)
              .uploadId(uploadId)
              .build());
      throw new IOException("Failed server-side S3 UploadPartCopy merge for key " + targetKey, e);
    }
  }

  private String mergeUsingStreamingReupload(String targetKey, List<UploadInfo> partialUploads)
      throws IOException {
    InputStream combinedStream =
        new SequenceInputStream(
            new UploadInputStreamEnumeration(partialUploads, uploadStorageService));

    CreateMultipartUploadResponse createResponse =
        s3Client.createMultipartUpload(
            CreateMultipartUploadRequest.builder().bucket(bucket).key(targetKey).build());
    String uploadId = createResponse.uploadId();

    List<CompletedPart> completedParts = new ArrayList<>();
    int partNumber = 1;
    byte[] buffer = new byte[8192];

    try {
      boolean done = false;
      while (!done) {
        File tempFile = File.createTempFile("tus-s3-concat-", ".tmp", temporaryDirectory.toFile());
        tempFile.deleteOnExit();

        long bytesWritten = 0;
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
          int bytesRead;
          while (bytesWritten < minPartSize && (bytesRead = combinedStream.read(buffer)) != -1) {
            fos.write(buffer, 0, bytesRead);
            bytesWritten += bytesRead;
          }
          if (bytesWritten < minPartSize) {
            done = true;
          }
        }

        if (bytesWritten > 0) {
          try (FileInputStream fis = new FileInputStream(tempFile)) {
            software.amazon.awssdk.services.s3.model.UploadPartResponse partResponse =
                s3Client.uploadPart(
                    UploadPartRequest.builder()
                        .bucket(bucket)
                        .key(targetKey)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength(bytesWritten)
                        .build(),
                    RequestBody.fromInputStream(fis, bytesWritten));

            completedParts.add(
                CompletedPart.builder().partNumber(partNumber).eTag(partResponse.eTag()).build());
            partNumber++;
          }
        }

        tempFile.delete();
      }

      s3Client.completeMultipartUpload(
          software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(targetKey)
              .uploadId(uploadId)
              .multipartUpload(
                  software.amazon.awssdk.services.s3.model.CompletedMultipartUpload.builder()
                      .parts(completedParts)
                      .build())
              .build());
      return uploadId;
    } catch (Exception e) {
      s3Client.abortMultipartUpload(
          software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(targetKey)
              .uploadId(uploadId)
              .build());
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
