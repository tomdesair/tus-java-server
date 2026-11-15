package me.desair.tus.server.upload.s3;

import io.minio.AbortMultipartUploadArgs;
import io.minio.Checksum;
import io.minio.CompleteMultipartUploadArgs;
import io.minio.ComposeObjectArgs;
import io.minio.CreateMultipartUploadArgs;
import io.minio.CreateMultipartUploadResponse;
import io.minio.Http;
import io.minio.MinioAsyncClient;
import io.minio.MinioClient;
import io.minio.Signer;
import io.minio.SourceObject;
import io.minio.Time;
import io.minio.Utils;
import io.minio.credentials.Credentials;
import io.minio.credentials.Provider;
import io.minio.credentials.StaticProvider;
import io.minio.messages.Part;
import java.io.Closeable;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.desair.tus.server.upload.UploadInfo;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper executing native S3 Multipart Copy operations to combine part objects server-side.
 *
 * <p>Why this helper exists: MinIO Java SDK's {@code composeObject()} implementation delegates to
 * {@code UploadPartCopy} with an internal {@code EMPTY_BODY} placeholder. In MinIO SDK's {@code
 * Http.toRequest()}, that placeholder automatically attaches {@code Content-MD5} and {@code
 * Content-Type} headers to the HTTP request. While MinIO server ignores those extra headers, Amazon
 * AWS S3 strictly forbids the {@code Content-MD5} header on {@code UploadPartCopy} requests,
 * returning HTTP 400 InvalidArgument ("The specified header is not valid in this context").
 * Attempting to remove the header via an OkHttp network interceptor also fails because AWS SigV4
 * signature calculation includes all present headers, resulting in a SignatureDoesNotMatch error.
 *
 * <p>Workaround: This helper executes standard S3 Multipart Upload Copy operations directly via
 * OkHttp and AWS SigV4 signing without adding {@code Content-MD5}. This allows fast zero-bandwidth
 * server-side composition on both Amazon AWS S3 and MinIO/Ceph clusters without needing heavy
 * external AWS SDK dependencies.
 *
 * <p>Safety Fallback: If native server-side compose fails for any reason (e.g. S3 permissions,
 * regional restrictions, or client reflection unavailability), callers (such as {@link
 * S3StorageService} and {@link S3ConcatenationService}) catch the exception and fall back to
 * sequential streaming concatenation (re-uploading the combined parts).
 */
public class S3ServerSideComposeHelper implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(S3ServerSideComposeHelper.class);

  private final MinioClient minioClient;
  private final MinioAsyncClient asyncClient;
  private final Http.BaseUrl baseUrl;
  private final Provider provider;
  private final OkHttpClient httpClient;
  private final String explicitRegion;
  private final ReadWriteLock thisObjectLock = new ReentrantReadWriteLock();
  private boolean composeSupported = true;

  /**
   * Constructs an instance with explicit connection parameters without requiring a {@link
   * MinioClient}.
   *
   * @param endpoint The S3 endpoint URL (e.g. "https://s3.amazonaws.com" or
   *     "http://localhost:9000")
   * @param region S3 region name (optional, defaults to "local" if null or empty)
   * @param accessKey S3 access key / username
   * @param secretKey S3 secret key / password
   */
  public S3ServerSideComposeHelper(
      String endpoint, String region, String accessKey, String secretKey) {
    this(null, endpoint, region, accessKey, secretKey);
  }

  /**
   * Constructs an instance wrapping the given {@link MinioClient} without reflection.
   *
   * <p>Package-private constructor for internal testing.
   *
   * @param minioClient The MinIO client instance
   */
  S3ServerSideComposeHelper(MinioClient minioClient) {
    this(minioClient, null, null, null, null);
  }

  S3ServerSideComposeHelper(
      MinioClient minioClient, String endpoint, String region, String accessKey, String secretKey) {
    this.explicitRegion = (region != null && !region.isEmpty()) ? region : "local";

    Http.BaseUrl base = null;
    Provider prov = null;
    OkHttpClient client = null;
    MinioAsyncClient async = null;
    MinioClient syncClient = minioClient;

    if (endpoint != null && !endpoint.isEmpty()) {
      try {
        base = new Http.BaseUrl(endpoint);
        if (region != null && !region.isEmpty()) {
          base.setRegion(region);
        }
        prov = new StaticProvider(accessKey, secretKey, null);
        // Server-side UploadPartCopy on multi-hundred-MB or multi-GB S3 parts regularly takes
        // longer than OkHttp's default 10-second timeout. Configure generous cloud copy timeouts.
        client =
            new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();

        MinioAsyncClient.Builder asyncBuilder =
            MinioAsyncClient.builder().endpoint(endpoint).credentials(accessKey, secretKey);
        if (region != null && !region.isEmpty()) {
          asyncBuilder.region(region);
        }
        async = asyncBuilder.build();
        if (syncClient == null) {
          syncClient = S3Utils.buildMinioClient(endpoint, region, accessKey, secretKey);
        }
      } catch (Exception e) {
        log.warn(
            "Failed to initialize native S3 connection from endpoint {}: {}",
            endpoint,
            e.getMessage());
      }
    }

    this.minioClient = syncClient;
    this.baseUrl = base;
    this.provider = prov;
    this.httpClient = client;
    this.asyncClient = async;
  }

  /**
   * Returns whether native S3 multipart copy is available with the extracted client properties.
   *
   * @return true if all required fields are accessible; false otherwise
   */
  public boolean isAvailable() {
    return asyncClient != null && baseUrl != null && httpClient != null;
  }

  /**
   * Returns whether S3 server-side composition is supported by the storage endpoint.
   *
   * @return {@code true} if server-side compose is supported; {@code false} if disabled or failed
   */
  public boolean isComposeSupported() {
    thisObjectLock.readLock().lock();
    try {
      return composeSupported;
    } finally {
      thisObjectLock.readLock().unlock();
    }
  }

  /**
   * Configures whether S3 server-side composition is supported by the storage endpoint.
   *
   * @param composeSupported {@code true} to enable; {@code false} to disable
   */
  public void setComposeSupported(boolean composeSupported) {
    thisObjectLock.writeLock().lock();
    try {
      this.composeSupported = composeSupported;
    } finally {
      thisObjectLock.writeLock().unlock();
    }
  }

  /**
   * Checks whether the given part keys meet S3's minimum part size requirement for server-side
   * composition (all parts except the final part must be at least {@code minPartSize} bytes).
   *
   * <p>Part sizes are resolved in O(1) time from the {@code _<size>} suffix encoded in each part
   * key name, falling back to {@code statObject} only for legacy keys without a size suffix.
   *
   * @param bucket The S3 bucket name
   * @param partKeys The ordered list of part keys to compose
   * @param minPartSize Minimum required byte size for non-final parts (typically 5 MiB)
   * @return {@code true} if server-side composition can be used; {@code false} otherwise
   */
  public boolean canCompose(String bucket, List<String> partKeys, long minPartSize) {
    if (!isComposeSupported() || partKeys == null || partKeys.isEmpty()) {
      return false;
    }
    // AWS S3 requires all parts except the last to be >= minPartSize (5 MB)
    for (int i = 0; i < partKeys.size() - 1; i++) {
      if (S3Utils.getPartSize(minioClient, bucket, partKeys.get(i)) < minPartSize) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether the given partial uploads meet S3's constraints for server-side composition.
   *
   * <p>AWS S3 composeObject / multipart upload constraints:
   *
   * <ul>
   *   <li>Single part: must be &ge; minPartSize (5MB) and have a valid storageUploadId.
   *   <li>Multiple parts (&ge; 2): all parts EXCEPT the final part must be &ge; minPartSize (5MB)
   *       and all parts must have valid storageUploadId. The final part is allowed to be &lt; 5MB.
   * </ul>
   *
   * @param partialUploads The partial uploads to check
   * @param minPartSize Minimum required byte size for non-final parts (typically 5 MiB)
   * @return {@code true} if server-side composition can be used; {@code false} otherwise
   */
  public boolean canComposePartialUploads(List<UploadInfo> partialUploads, long minPartSize) {
    if (!isComposeSupported() || partialUploads == null || partialUploads.isEmpty()) {
      return false;
    }
    if (partialUploads.size() == 1) {
      UploadInfo p = partialUploads.get(0);
      return p.getLength() != null
          && p.getLength() >= minPartSize
          && p.getStorageUploadId() != null;
    }
    for (int i = 0; i < partialUploads.size(); i++) {
      UploadInfo p = partialUploads.get(i);
      if (p.getLength() == null || p.getStorageUploadId() == null) {
        return false;
      }
      if (i < partialUploads.size() - 1 && p.getLength() < minPartSize) {
        return false;
      }
    }
    return true;
  }

  /**
   * Composes the given part keys into the target object key entirely server-side on S3.
   *
   * @param bucket The S3 bucket name
   * @param targetKey The destination object key
   * @param partKeys The source part object keys to combine in order
   * @throws Exception If composition fails
   */
  public void compose(String bucket, String targetKey, List<String> partKeys) throws Exception {
    if (!isAvailable()) {
      // Fallback for mocked MinioClient instances in tests
      List<SourceObject> sources = new ArrayList<>();
      for (String pk : partKeys) {
        sources.add(SourceObject.builder().bucket(bucket).object(pk).build());
      }
      minioClient.composeObject(
          ComposeObjectArgs.builder().bucket(bucket).object(targetKey).sources(sources).build());
      return;
    }

    String region = baseUrl.region();
    if (region == null || region.isEmpty()) {
      region = explicitRegion;
    }
    if (region == null || region.isEmpty()) {
      region = "local";
    }

    Credentials credentials = (provider != null) ? provider.fetch() : null;

    // 1. Create Multipart Upload
    CreateMultipartUploadArgs createArgs =
        CreateMultipartUploadArgs.builder().bucket(bucket).object(targetKey).build();
    CreateMultipartUploadResponse createResponse =
        asyncClient.createMultipartUpload(createArgs).join();
    String uploadId = createResponse.result().uploadId();
    log.info(
        "Started server-side multipart copy upload (ID: {}) for target {} in bucket {} with {} parts",
        uploadId,
        targetKey,
        bucket,
        partKeys.size());

    List<Part> parts = new ArrayList<>();
    try {
      // 2. UploadPartCopy for each part chunk without Content-MD5
      for (int i = 0; i < partKeys.size(); i++) {
        int partNumber = i + 1;
        String partKey = partKeys.get(i);

        Http.QueryParameters queryParams =
            new Http.QueryParameters(
                "partNumber", Integer.toString(partNumber), "uploadId", uploadId);
        HttpUrl url = baseUrl.buildUrl(Http.Method.PUT, bucket, targetKey, region, queryParams);

        // x-amz-copy-source must be URL-encoded "/bucket/key"
        String copySource = "/" + bucket + "/" + Utils.encodePath(partKey);
        String now = ZonedDateTime.now().format(Time.AMZ_DATE_FORMAT);

        Request.Builder requestBuilder =
            new Request.Builder()
                .url(url)
                .put(RequestBody.create(new byte[0], null))
                .header("Host", Utils.getHostHeader(url))
                .header("x-amz-date", now)
                .header("x-amz-copy-source", copySource)
                .header("x-amz-content-sha256", Checksum.ZERO_SHA256_HASH);

        if (credentials != null
            && credentials.sessionToken() != null
            && !credentials.sessionToken().isEmpty()) {
          requestBuilder.header("x-amz-security-token", credentials.sessionToken());
        }

        Request httpRequest = requestBuilder.build();
        if (credentials != null) {
          httpRequest =
              Signer.signV4S3(
                  httpRequest,
                  region,
                  credentials.accessKey(),
                  credentials.secretKey(),
                  Checksum.ZERO_SHA256_HASH);
        }

        try (Response response = httpClient.newCall(httpRequest).execute()) {
          if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "";
            throw new IOException(
                "S3 UploadPartCopy failed for part "
                    + partNumber
                    + " ("
                    + partKey
                    + "): HTTP "
                    + response.code()
                    + " "
                    + errorBody);
          }
          String responseBody = response.body() != null ? response.body().string() : "";
          String etag = extractEtagFromXml(responseBody);
          if (etag == null || etag.isEmpty()) {
            etag = response.header("ETag");
          }
          if (etag == null || etag.isEmpty()) {
            throw new IOException("Missing ETag in UploadPartCopy response for part " + partNumber);
          }
          parts.add(new Part(partNumber, etag));
        }
      }

      // 3. Complete Multipart Upload
      CompleteMultipartUploadArgs completeArgs =
          CompleteMultipartUploadArgs.builder()
              .bucket(bucket)
              .object(targetKey)
              .uploadId(uploadId)
              .parts(parts.toArray(new Part[0]))
              .build();
      asyncClient.completeMultipartUpload(completeArgs).join();
      log.info(
          "Successfully completed server-side multipart copy upload (ID: {}) for target {} in bucket {}",
          uploadId,
          targetKey,
          bucket);

    } catch (Exception e) {
      try {
        asyncClient
            .abortMultipartUpload(
                AbortMultipartUploadArgs.builder()
                    .bucket(bucket)
                    .object(targetKey)
                    .uploadId(uploadId)
                    .build())
            .join();
      } catch (Exception abortEx) {
        log.warn("Failed to abort multipart upload {} for target {}", uploadId, targetKey, abortEx);
      }
      throw e;
    }
  }

  /** Closes any HTTP client dispatchers and evicts connection pools to release native threads. */
  @Override
  public void close() {
    if (httpClient != null) {
      httpClient.dispatcher().executorService().shutdown();
      httpClient.connectionPool().evictAll();
    }
  }

  /**
   * Extracts the ETag value from an S3 {@code CopyPartResult} XML string.
   *
   * @param xml The XML response from S3 UploadPartCopy
   * @return Extracted ETag string, or null if not found
   */
  static String extractEtagFromXml(String xml) {
    if (xml == null) {
      return null;
    }
    int start = xml.indexOf("<ETag>");
    int end = xml.indexOf("</ETag>");
    if (start != -1 && end != -1 && end > start + 6) {
      String etag = xml.substring(start + 6, end).trim();
      etag = etag.replace("&quot;", "\"");
      return etag;
    }
    return null;
  }
}
