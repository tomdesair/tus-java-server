package me.desair.tus.server.upload.s3;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.checksum.ChecksumIndex;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S3-compatible implementation of {@link ChecksumIndex}.
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
public class S3ChecksumIndex implements ChecksumIndex {

  private static final Logger log = LoggerFactory.getLogger(S3ChecksumIndex.class);

  private static final String DEFAULT_CHECKSUMS_PREFIX = "checksums/";
  private static final String PARENT_OBJECT = "parent";
  private static final String PARENT_DELETED_OBJECT = "parent-deleted";
  private static final String CHILDREN_PREFIX = "children/";

  private final MinioClient minioClient;
  private final String bucket;
  private final String checksumsPrefix;

  private final ReadWriteLock thisObjectLock = new ReentrantReadWriteLock();
  private boolean s3ConditionalWritesSupported = true;

  /**
   * Constructs a new {@link S3ChecksumIndex}.
   *
   * @param minioClient the MinioClient instance
   * @param bucket target bucket name
   */
  public S3ChecksumIndex(MinioClient minioClient, String bucket) {
    this(minioClient, bucket, DEFAULT_CHECKSUMS_PREFIX);
  }

  /**
   * Constructs a new {@link S3ChecksumIndex} with a custom checksums prefix.
   *
   * @param minioClient the MinioClient instance
   * @param bucket target bucket name
   * @param checksumsPrefix the prefix path for checksums
   */
  public S3ChecksumIndex(MinioClient minioClient, String bucket, String checksumsPrefix) {
    this.minioClient = Objects.requireNonNull(minioClient, "MinioClient must not be null");
    this.bucket = Objects.requireNonNull(bucket, "Bucket must not be null");
    this.checksumsPrefix = S3Utils.sanitizePrefix(checksumsPrefix);
  }

  /**
   * Returns whether conditional writes (If-None-Match: *) are supported by the S3 endpoint.
   *
   * @return {@code true} if supported, {@code false} otherwise
   */
  public boolean isS3ConditionalWritesSupported() {
    thisObjectLock.readLock().lock();
    try {
      return s3ConditionalWritesSupported;
    } finally {
      thisObjectLock.readLock().unlock();
    }
  }

  /**
   * Configures whether conditional writes (If-None-Match: *) should be attempted.
   *
   * @param supported {@code true} to enable, {@code false} to disable
   */
  public void setS3ConditionalWritesSupported(boolean supported) {
    thisObjectLock.writeLock().lock();
    try {
      this.s3ConditionalWritesSupported = supported;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
  }

  @Override
  public boolean tryRegisterParent(ChecksumKey key, UploadId uploadId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(uploadId, "uploadId must not be null");

    String parentKey = buildKey(key, PARENT_OBJECT);
    byte[] content = uploadId.toString().getBytes(StandardCharsets.UTF_8);

    boolean conditionalSupported;
    thisObjectLock.readLock().lock();
    try {
      conditionalSupported = s3ConditionalWritesSupported;
    } finally {
      thisObjectLock.readLock().unlock();
    }

    if (conditionalSupported) {
      try {
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .object(parentKey)
                .extraHeaders(Collections.singletonMap("If-None-Match", "*"))
                .stream(new ByteArrayInputStream(content), (long) content.length, -1L)
                .build());
        return true;
      } catch (ErrorResponseException e) {
        S3ErrorType errorType = S3Utils.parseErrorResponse(e);
        if (errorType == S3ErrorType.PRECONDITION_FAILED || errorType == S3ErrorType.CONFLICT) {
          // Object already exists (lost CAS race, whether active or tombstoned)
          return false;
        }
        if (errorType == S3ErrorType.API_NOT_IMPLEMENTED) {
          log.info("S3 endpoint does not support If-None-Match on putObject; downgrading CAS");
          thisObjectLock.writeLock().lock();
          try {
            s3ConditionalWritesSupported = false;
          } finally {
            thisObjectLock.writeLock().unlock();
          }
          // Fall through to non-CAS mode
        } else {
          throw new IOException("Failed to put parent index object in S3: " + parentKey, e);
        }
      } catch (Exception e) {
        throw new IOException("Failed to put parent index object in S3: " + parentKey, e);
      }
    }

    // Non-CAS fallback mode (fail-closed check)
    if (objectExists(parentKey)) {
      return false;
    }
    putObjectUnconditional(parentKey, content);
    return true;
  }

  @Override
  public UploadId findParent(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String parentKey = buildKey(key, PARENT_OBJECT);

    try (InputStream stream = S3Utils.getObjectInputStream(minioClient, bucket, parentKey)) {
      String idStr = IOUtils.toString(stream, StandardCharsets.UTF_8).trim();
      return idStr.isEmpty() ? null : new UploadId(idStr);
    } catch (ErrorResponseException e) {
      if (S3Utils.parseErrorResponse(e) == S3ErrorType.NO_SUCH_KEY) {
        return null;
      }
      throw new IOException("Failed to get parent index object from S3: " + parentKey, e);
    } catch (Exception e) {
      throw new IOException("Failed to get parent index object from S3: " + parentKey, e);
    }
  }

  @Override
  public void markParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String parentDeletedKey = buildKey(key, PARENT_DELETED_OBJECT);

    // Write empty tombstone marker while keeping parent pointer object intact
    // until deleteIndex(key) is invoked when the last child terminates.
    putObjectUnconditional(parentDeletedKey, new byte[0]);
  }

  @Override
  public boolean isParentDeleted(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    return objectExists(buildKey(key, PARENT_DELETED_OBJECT));
  }

  @Override
  public void addChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");
    String childKey = buildKey(key, CHILDREN_PREFIX + childId);
    putObjectUnconditional(childKey, new byte[0]);
  }

  @Override
  public void removeChild(ChecksumKey key, UploadId childId) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(childId, "childId must not be null");
    String childKey = buildKey(key, CHILDREN_PREFIX + childId);
    S3Utils.deleteS3ObjectQuietly(minioClient, bucket, childKey);
  }

  @Override
  public boolean hasChildren(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String prefix = buildKey(key, CHILDREN_PREFIX);

    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder().bucket(bucket).prefix(prefix).maxKeys(1).build());
      for (Result<Item> result : results) {
        if (result != null && result.get() != null) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      throw new IOException("Failed to list children in S3 for prefix " + prefix, e);
    }
  }

  @Override
  public void deleteIndex(ChecksumKey key) throws IOException {
    Objects.requireNonNull(key, "key must not be null");
    String prefix = buildKey(key, "");

    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build());

      List<String> toDelete =
          StreamSupport.stream(results.spliterator(), false)
              .map(S3Utils::extractObjectName)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());

      S3Utils.deleteS3ObjectsQuietly(minioClient, bucket, toDelete);
    } catch (Exception e) {
      throw new IOException("Failed to delete checksum index in S3 for prefix " + prefix, e);
    }
  }

  @Override
  public Set<ChecksumKey> getAllIndexedKeys() throws IOException {
    Set<ChecksumKey> keys = new LinkedHashSet<>();
    try {
      Iterable<Result<Item>> results =
          minioClient.listObjects(
              ListObjectsArgs.builder()
                  .bucket(bucket)
                  .prefix(checksumsPrefix)
                  .recursive(true)
                  .build());
      for (Result<Item> result : results) {
        Item item = result.get();
        if (item == null || item.objectName() == null) {
          continue;
        }
        String objectName = item.objectName();
        if (objectName.endsWith("/" + PARENT_OBJECT)) {
          String relative = objectName.substring(checksumsPrefix.length());
          if (relative.startsWith("/")) {
            relative = relative.substring(1);
          }
          String[] parts = relative.split("/");
          if (parts.length == 3 && PARENT_OBJECT.equals(parts[2])) {
            ChecksumAlgorithm algo = ChecksumAlgorithm.forTusName(parts[0]);
            if (algo != null) {
              try {
                keys.add(ChecksumKey.of(parts[1], algo));
              } catch (IllegalArgumentException e) {
                // Malformed checksum key, skip
              }
            }
          }
        }
      }
    } catch (Exception e) {
      throw new IOException("Failed to list indexed checksum keys from S3", e);
    }
    return keys;
  }

  private String buildKey(ChecksumKey key, String subPath) {
    return checksumsPrefix + key + (subPath.isEmpty() ? "" : "/" + subPath);
  }

  private boolean objectExists(String objectKey) {
    return S3Utils.objectExists(minioClient, bucket, objectKey);
  }

  private void putObjectUnconditional(String objectKey, byte[] data) throws IOException {
    try {
      minioClient.putObject(
          PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(
                  new ByteArrayInputStream(data), (long) data.length, -1L)
              .build());
    } catch (Exception e) {
      throw new IOException("Failed to put object in S3: " + objectKey, e);
    }
  }
}
