package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.azure.storage.blob.BlobContainerClient;
import jakarta.servlet.http.HttpServletResponse;
import me.desair.tus.server.AbstractITTusFileUploadService;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
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
}
