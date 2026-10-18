package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.ListBlobsOptions;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.AbstractITTusFileUploadService;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

/**
 * End-to-end integration test suite verifying {@link TusFileUploadService} backed by {@link
 * AzureBlobStorageService} and {@link AzureBlobLockingService} on Azurite using the official Azure
 * Storage Blob SDK. Extends {@link AbstractITTusFileUploadService} to run all Tus 1.0.0 protocol
 * use cases against Azure Blob Storage.
 */
public class ITAzureBlobTusFileUploadService extends AbstractITTusFileUploadService {

  private static GenericContainer<?> azurite;
  private static BlobContainerClient containerClient;
  private static final String CONTAINER = "test-tus-azure-container";

  @BeforeClass
  public static void setUpClass() {
    org.junit.Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers Azurite test",
        TestUtils.isContainerRuntimeAvailable());

    azurite = TestUtils.createAzuriteContainer();
    azurite.start();

    containerClient = TestUtils.createBlobContainerClient(azurite, CONTAINER);
  }

  @AfterClass
  public static void tearDownClass() {
    if (azurite != null) {
      azurite.stop();
    }
  }

  @Override
  protected TusFileUploadService createTusFileUploadService() {
    return createTusFileUploadService(UPLOAD_URI);
  }

  @Override
  protected TusFileUploadService createTusFileUploadService(String uploadUri) {
    org.junit.Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable());

    AzureBlobStorageService azureStorage = new AzureBlobStorageService(containerClient);
    AzureBlobLockingService azureLocking = new AzureBlobLockingService(containerClient);
    AzureBlobConcatenationService azureConcat =
        new AzureBlobConcatenationService(containerClient, azureStorage);
    azureStorage.setUploadConcatenationService(azureConcat);

    return new TusFileUploadService()
        .withUploadUri(uploadUri)
        .withUploadStorageService(azureStorage)
        .withUploadLockingService(azureLocking)
        .withMaxUploadSize(1073741824L)
        .withUploadExpirationPeriod(2L * 24 * 60 * 60 * 1000)
        .withSupportedProtocolVersions(ProtocolVersion.TUS_1_0_0)
        .withDownloadFeature()
        .withChunkedTransferDecoding(true);
  }

  /**
   * Verifies that when an upload completes via PATCH (offset reaching total length), the lock blob
   * in the locks/ directory is automatically cleaned up and deleted.
   */
  @Test
  public void testUploadCompletionCleansUpLockBlob() throws Exception {
    String uploadContent = "Testing Azure lock blob cleanup on upload completion!";
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_CREATED, servletResponse.getStatus());

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);
    String uploadIdStr = StringUtils.substringAfterLast(location, "/");

    // Complete the upload via PATCH
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    // Verify lock blob is cleaned up on completion
    assertFalse(
        "Lock blob should be removed upon upload completion",
        containerClient.getBlobClient("locks/" + uploadIdStr + ".lock").exists());
    assertFalse(
        "Stop signal blob should not exist",
        containerClient.getBlobClient("locks/" + uploadIdStr + ".stop").exists());
  }

  /**
   * Verifies that when an in-progress upload is terminated via DELETE, the lock blob in the locks/
   * directory is automatically cleaned up and deleted.
   */
  @Test
  public void testDeleteTerminationCleansUpLockBlob() throws Exception {
    String uploadContent = "Incomplete upload to terminate";
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, uploadContent.getBytes().length * 2);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_CREATED, servletResponse.getStatus());

    String location =
        UPLOAD_URI
            + StringUtils.substringAfter(
                servletResponse.getHeader(HttpHeader.LOCATION), UPLOAD_URI);
    String uploadIdStr = StringUtils.substringAfterLast(location, "/");

    // Partially upload via PATCH
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, 0);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    servletRequest.setContent(uploadContent.getBytes());

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    // Delete the upload
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(location);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    // Verify lock blob is cleaned up on termination
    assertFalse(
        "Lock blob should be removed upon DELETE termination",
        containerClient.getBlobClient("locks/" + uploadIdStr + ".lock").exists());
    assertFalse(
        "Stop signal blob should not exist",
        containerClient.getBlobClient("locks/" + uploadIdStr + ".stop").exists());
  }

  /**
   * Azure Integration Test: Verifies that during the end-to-end Tus protocol lifecycle, the
   * physical checksum index blobs in Azure Blob storage are created and properly purged on final
   * deletion.
   */
  @Test
  public void testPhysicalChecksumDirectoryCreationAndCleanupInAzureBlob() throws Exception {
    tusFileUploadService.withUploadDeduplication(true);

    String content = "Physical Azure Checksum Verification Payload";
    byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
    byte[] sha1Digest = org.apache.commons.codec.digest.DigestUtils.sha1(contentBytes);
    String sha1Hex = org.apache.commons.codec.binary.Hex.encodeHexString(sha1Digest);
    String sha1Base64 = org.apache.commons.codec.binary.Base64.encodeBase64String(sha1Digest);

    String parentBlobName = "checksums/sha1/" + sha1Hex + "/parent";
    String parentDeletedBlobName = "checksums/sha1/" + sha1Hex + "/parent-deleted";

    // 1. Create parent upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, contentBytes.length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_CREATED, servletResponse.getStatus());
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
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    // Assert physical parent pointer exists in Azure container
    assertTrue(containerClient.getBlobClient(parentBlobName).exists());

    // 2. Create duplicate child upload
    reset();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, contentBytes.length);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_CREATED, servletResponse.getStatus());
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
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());
    assertEquals(parentInfo.getId(), childInfo.getDuplicatesUploadId());

    String childBlobName = "checksums/sha1/" + sha1Hex + "/children/" + childInfo.getId();
    assertTrue(containerClient.getBlobClient(childBlobName).exists());

    // 3. Delete parent upload via DELETE
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(parentUri);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    assertTrue(containerClient.getBlobClient(parentDeletedBlobName).exists());

    // 4. Delete child upload via DELETE (last child out)
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(childUri);
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertEquals(HttpServletResponse.SC_NO_CONTENT, servletResponse.getStatus());

    assertFalse(containerClient.getBlobClient(parentBlobName).exists());
    assertFalse(containerClient.getBlobClient(parentDeletedBlobName).exists());
    assertFalse(
        containerClient
            .listBlobs(new ListBlobsOptions().setPrefix("checksums/sha1/" + sha1Hex), null)
            .iterator()
            .hasNext());
  }

  @Override
  protected void deleteUploadedBytesOutOfBand(UploadId uploadId) throws Exception {
    containerClient.getBlobClient("uploads/" + uploadId).deleteIfExists();
  }
}
