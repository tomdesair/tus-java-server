package me.desair.tus.server.upload.azure;

import com.azure.storage.blob.BlobContainerClient;
import me.desair.tus.server.AbstractITTusFileUploadService;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.TusFileUploadService;
import org.junit.AfterClass;
import org.junit.BeforeClass;
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
}
