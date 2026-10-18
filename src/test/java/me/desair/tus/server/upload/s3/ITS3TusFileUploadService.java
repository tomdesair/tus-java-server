package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.messages.Item;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.AbstractITTusFileUploadService;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * End-to-end integration test suite verifying {@link TusFileUploadService} backed by {@link
 * S3StorageService} and {@link S3LockingService} on MinIO using the MinIO Java SDK. Extends {@link
 * AbstractITTusFileUploadService} to run all Tus 1.0.0 protocol use cases against S3 storage.
 */
public class ITS3TusFileUploadService extends AbstractITTusFileUploadService {

  private static GenericContainer<?> rustfsContainer;
  private static MinioClient minioClient;
  private static final String BUCKET = "test-service-s3-bucket";

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers S3 (RustFS) test",
        TestUtils.isContainerRuntimeAvailable());

    rustfsContainer = TestUtils.createRustFsContainer();
    rustfsContainer.start();

    minioClient = TestUtils.createMinioClient(rustfsContainer);
    TestUtils.createBucket(minioClient, BUCKET);
  }

  @AfterClass
  public static void tearDownClass() {
    if (rustfsContainer != null) {
      rustfsContainer.stop();
    }
  }

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    return createTusFileUploadService(UPLOAD_URI);
  }

  @Override
  protected TusFileUploadService createTusFileUploadService(String uploadUri) {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    String endpoint = TestUtils.getS3Endpoint(rustfsContainer);
    S3StorageService s3Storage =
        new S3StorageService(endpoint, "rustfsadmin", "rustfsadmin", BUCKET);
    S3LockingService s3Locking =
        new S3LockingService(endpoint, "rustfsadmin", "rustfsadmin", BUCKET);
    S3ConcatenationService s3Concat =
        new S3ConcatenationService(endpoint, "rustfsadmin", "rustfsadmin", BUCKET, s3Storage);
    s3Storage.setUploadConcatenationService(s3Concat);

    return new TusFileUploadService()
        .withUploadUri(uploadUri)
        .withUploadStorageService(s3Storage)
        .withUploadLockingService(s3Locking)
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0)
        .withDownloadFeature()
        .withChunkedTransferDecoding(true);
  }

  /**
   * S3 Integration Test: Verifies that during the end-to-end Tus protocol lifecycle, the physical
   * checksum index objects in S3 are created and properly purged on final deletion.
   */
  @Test
  public void testPhysicalChecksumDirectoryCreationAndCleanupInS3() throws Exception {
    tusFileUploadService.withUploadDeduplication(true);

    String content = "Physical S3 Checksum Verification Payload";
    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
    byte[] sha1Digest = org.apache.commons.codec.digest.DigestUtils.sha1(contentBytes);
    String sha1Hex = org.apache.commons.codec.binary.Hex.encodeHexString(sha1Digest);
    String sha1Base64 = org.apache.commons.codec.binary.Base64.encodeBase64String(sha1Digest);

    String parentKey = "checksums/sha1/" + sha1Hex + "/parent";
    String parentDeletedKey = "checksums/sha1/" + sha1Hex + "/parent-deleted";

    // 1. Create parent upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, contentBytes.length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String parentUri = servletResponse.getHeader(HttpHeader.LOCATION);

    // Complete parent upload via PATCH with checksum
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(parentUri);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 " + sha1Base64);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(contentBytes);
    UploadInfo parentInfo =
        tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Assert physical parent pointer exists in S3
    assertNotNull(
        minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(parentKey).build()));

    // 2. Create duplicate child upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, contentBytes.length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String childUri = servletResponse.getHeader(HttpHeader.LOCATION);

    // Complete child upload via PATCH with same checksum
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(childUri);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 " + sha1Base64);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(contentBytes);
    UploadInfo childInfo = tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertEquals(parentInfo.getId(), childInfo.getDuplicatesUploadId());

    String childKey = "checksums/sha1/" + sha1Hex + "/children/" + childInfo.getId();
    assertNotNull(
        minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(childKey).build()));

    // 3. Delete parent upload via DELETE
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(parentUri);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    assertNotNull(
        minioClient.statObject(
            StatObjectArgs.builder().bucket(BUCKET).object(parentDeletedKey).build()));

    // 4. Delete child upload via DELETE (last child out)
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(childUri);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    Iterable<Result<Item>> remaining =
        minioClient.listObjects(
            ListObjectsArgs.builder().bucket(BUCKET).prefix("checksums/sha1/" + sha1Hex).build());
    assertFalse(remaining.iterator().hasNext());
  }

  @Override
  protected void deleteUploadedBytesOutOfBand(UploadId uploadId) throws Exception {
    minioClient.removeObject(
        RemoveObjectArgs.builder().bucket(BUCKET).object("uploads/" + uploadId).build());
  }
}
