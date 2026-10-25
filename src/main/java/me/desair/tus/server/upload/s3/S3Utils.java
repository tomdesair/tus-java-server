package me.desair.tus.server.upload.s3;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility helper methods for interacting with MinIO and S3-compatible object storage backends. */
public final class S3Utils {

  private static final Logger log = LoggerFactory.getLogger(S3Utils.class);

  private S3Utils() {
    // Utility class
  }

  /**
   * Instantiates a pre-configured {@link MinioClient} with region fallback.
   *
   * @param endpoint S3 endpoint URL (e.g. "https://s3.amazonaws.com" or "http://localhost:9000")
   * @param region S3 region name (defaults to "local" if null or empty)
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   * @return Configured {@link MinioClient} instance
   */
  public static MinioClient buildMinioClient(
      String endpoint, String region, String accessKey, String secretKey) {
    String effectiveRegion = (region != null && !region.isEmpty()) ? region : "local";
    return MinioClient.builder()
        .endpoint(endpoint)
        .credentials(accessKey, secretKey)
        .region(effectiveRegion)
        .build();
  }

  /**
   * Retrieves an {@link InputStream} for reading the contents of an S3 object.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param objectKey The S3 object key to fetch
   * @return {@link InputStream} representing the object content stream, or {@code null} if any
   *     argument is null
   * @throws Exception If an error occurs while communicating with the S3 service
   */
  public static InputStream getObjectInputStream(
      MinioClient minioClient, String bucket, String objectKey) throws Exception {
    if (minioClient == null || bucket == null || objectKey == null) {
      return null;
    }
    return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
  }

  /**
   * Deletes a single S3 object, throwing an exception if the S3 request fails.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param key The S3 object key to remove
   * @throws Exception If an error occurs during object deletion
   */
  public static void deleteS3Object(MinioClient minioClient, String bucket, String key)
      throws Exception {
    if (minioClient == null || bucket == null || key == null) {
      return;
    }
    minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
  }

  /**
   * Quietly deletes a single S3 object without throwing exceptions, logging errors at debug level.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param key The S3 object key to remove
   */
  public static void deleteS3ObjectQuietly(MinioClient minioClient, String bucket, String key) {
    try {
      deleteS3Object(minioClient, bucket, key);
    } catch (Exception e) {
      log.debug("Failed to delete S3 object key {} in bucket {}: {}", key, bucket, e.getMessage());
    }
  }

  /**
   * Quietly deletes multiple S3 objects in a single batch request using S3 Multi-Object Delete.
   *
   * <p>If batch multi-delete encounters an error (e.g. bucket policy restrictions or unsupported
   * multi-delete), safely falls back to individual object deletions.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param keys Collection of object keys to remove
   */
  public static void deleteS3ObjectsQuietly(
      MinioClient minioClient, String bucket, Collection<String> keys) {
    if (minioClient == null || bucket == null || keys == null || keys.isEmpty()) {
      return;
    }
    List<DeleteRequest.Object> objects =
        keys.stream()
            .filter(Objects::nonNull)
            .filter(k -> !k.trim().isEmpty())
            .map(DeleteRequest.Object::new)
            .collect(Collectors.toList());
    if (objects.isEmpty()) {
      return;
    }
    try {
      Iterable<Result<DeleteResult.Error>> results =
          minioClient.removeObjects(
              RemoveObjectsArgs.builder().bucket(bucket).objects(objects).build());
      if (results != null) {
        for (Result<DeleteResult.Error> res : results) {
          if (res != null) {
            // Must iterate over lazy results to trigger execution in MinIO SDK
            DeleteResult.Error error = res.get();
            if (error != null) {
              throw new IOException(
                  "Batch multi-delete failed for object "
                      + error.objectName()
                      + ": "
                      + error.message());
            }
          }
        }
      }
    } catch (Exception e) {
      log.debug(
          "Batch multi-delete failed in bucket {}, falling back to individual deletes: {}",
          bucket,
          e.getMessage());
      for (String key : keys) {
        deleteS3ObjectQuietly(minioClient, bucket, key);
      }
    }
  }

  /**
   * Extracts the object name from a MinIO {@link Result} of {@link Item}.
   *
   * @param result MinIO result item
   * @return The object name, or {@code null} if result or item is null
   * @throws RuntimeException If an error occurs retrieving the item from MinIO
   */
  public static String extractObjectName(Result<Item> result) {
    if (result == null) {
      return null;
    }
    try {
      Item item = result.get();
      return item != null ? item.objectName() : null;
    } catch (Exception e) {
      throw new RuntimeException("Failed to read S3 item result", e);
    }
  }

  /**
   * Retrieves the size in bytes of an S3 object via {@code statObject}.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param objectKey The S3 object key
   * @return The object size in bytes, or -1L if the object does not exist or an error occurs
   */
  public static long getObjectSize(MinioClient minioClient, String bucket, String objectKey) {
    if (minioClient == null || bucket == null || objectKey == null) {
      return -1L;
    }
    try {
      StatObjectResponse stat =
          minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
      return stat != null ? stat.size() : -1L;
    } catch (Exception e) {
      return -1L;
    }
  }

  /**
   * Resolves the byte size of an S3 part key.
   *
   * <p>If the key encodes its size suffix ({@code _<size>}), the byte count is extracted in O(1)
   * time without any remote network calls. If the key lacks a numeric suffix (e.g. legacy part keys
   * or mock test keys), this method falls back to {@link #getObjectSize} via {@code statObject}.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param partKey The S3 part object key
   * @return The part size in bytes, or 0L if the key is null or an error occurs
   */
  public static long getPartSize(MinioClient minioClient, String bucket, String partKey) {
    if (partKey == null) {
      return 0L;
    }
    int underscoreIdx = partKey.lastIndexOf('_');
    if (underscoreIdx >= 0 && underscoreIdx < partKey.length() - 1) {
      try {
        long parsed = Long.parseLong(partKey.substring(underscoreIdx + 1));
        if (parsed >= 0) {
          return parsed;
        }
      } catch (NumberFormatException ignored) {
        // Fall back to statObject if suffix is not numeric
      }
    }
    long size = getObjectSize(minioClient, bucket, partKey);
    return size >= 0 ? size : 0L;
  }

  /**
   * Checks whether an S3 object exists in the specified bucket.
   *
   * @param minioClient The {@link MinioClient}
   * @param bucket The target bucket name
   * @param objectKey The S3 object key to check
   * @return {@code true} if the object exists; {@code false} if it does not exist or an error
   *     occurs
   */
  public static boolean objectExists(MinioClient minioClient, String bucket, String objectKey) {
    if (minioClient == null || bucket == null || objectKey == null) {
      return false;
    }
    try {
      minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Parses an {@link ErrorResponseException} into a clean, strongly-typed {@link S3ErrorType}.
   *
   * @param exception The MinIO ErrorResponseException to evaluate
   * @return The corresponding S3ErrorType enum
   */
  public static S3ErrorType parseErrorResponse(ErrorResponseException exception) {
    if (exception == null) {
      return S3ErrorType.UNKNOWN;
    }

    ErrorResponse response = exception.errorResponse();
    String code = response != null && response.code() != null ? response.code() : "";

    if ("NoSuchKey".equalsIgnoreCase(code)
        || "NoSuchBucket".equalsIgnoreCase(code)
        || "NoSuchUpload".equalsIgnoreCase(code)) {
      return S3ErrorType.NO_SUCH_KEY;
    }

    if ("PreconditionFailed".equalsIgnoreCase(code)) {
      return S3ErrorType.PRECONDITION_FAILED;
    }

    if ("ObjectAlreadyExists".equalsIgnoreCase(code)
        || "BucketAlreadyExists".equalsIgnoreCase(code)) {
      return S3ErrorType.CONFLICT;
    }

    if ("AccessDenied".equalsIgnoreCase(code)) {
      return S3ErrorType.ACCESS_DENIED;
    }

    if ("APINotImplemented".equalsIgnoreCase(code)
        || "NotImplemented".equalsIgnoreCase(code)
        || (exception.response() != null && exception.response().code() == 501)) {
      return S3ErrorType.API_NOT_IMPLEMENTED;
    }

    return S3ErrorType.UNKNOWN;
  }
}
