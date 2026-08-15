package me.desair.tus.server.upload.azure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.azure.storage.blob.BlobContainerClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.TestUtils;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.MaxAppendSizeExceededException;
import me.desair.tus.server.exception.MinAppendSizeNotMetException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class ITAzureBlobStorageService {

  private static GenericContainer<?> azuriteContainer;

  @BeforeClass
  public static void setUpClass() {
    Assume.assumeTrue(
        "Container runtime is not available; skipping Testcontainers Azurite test",
        TestUtils.isContainerRuntimeAvailable());
    azuriteContainer = TestUtils.createAzuriteContainer();
    azuriteContainer.start();
  }

  @AfterClass
  public static void tearDownClass() {
    if (azuriteContainer != null) {
      azuriteContainer.stop();
    }
  }

  private BlobContainerClient containerClient;
  private AzureBlobStorageService storageService;

  @Before
  public void setUp() {
    Assume.assumeTrue(TestUtils.isContainerRuntimeAvailable() && azuriteContainer != null);
    containerClient =
        TestUtils.createBlobContainerClient(
            azuriteContainer, "unit-test-container-" + System.nanoTime());
    storageService = new AzureBlobStorageService(containerClient);
  }

  @Test(expected = MinAppendSizeNotMetException.class)
  public void minAppendSizeNotMetShouldThrow() throws Exception {
    storageService.setMinAppendSize(100L);

    UploadInfo info = new UploadInfo();
    info.setLength(500L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
  }

  @Test(expected = MaxAppendSizeExceededException.class)
  public void appendExceedsMaxAppendSizeShouldThrow() throws Exception {
    storageService.setMaxAppendSize(5L);

    UploadInfo info = new UploadInfo();
    info.setLength(500L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
  }

  @Test(expected = UploadNotFoundException.class)
  public void getUploadedBytesByUriNotFoundShouldThrow() throws Exception {
    storageService.getUploadedBytes("/test/upload/non-existent", "owner1");
  }

  @Test(expected = UploadNotFoundException.class)
  public void copyUploadToNotFoundShouldThrow() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("non-existent-id"));
    storageService.copyUploadTo(info, new ByteArrayOutputStream());
  }

  @Test
  public void createZeroLengthUploadShouldCommitEmptyDataBlob() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(0L);
    UploadInfo created = storageService.create(info, "owner1");

    assertNotNull(created.getId());
    assertEquals(Long.valueOf(0L), created.getOffset());

    UploadInfo fetched = storageService.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(0L), fetched.getOffset());
  }

  @Test
  public void appendConsecutiveSubThresholdChunksToPartBlob() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());

    storageService.append(created, new ByteArrayInputStream("abcdefghij".getBytes()));
    assertEquals(Long.valueOf(20L), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertEquals(
          "0123456789abcdefghij",
          org.apache.commons.io.IOUtils.toString(is, StandardCharsets.UTF_8));
    }
  }

  @Test
  public void maxAppendSizeFallback() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");
    storageService.setMaxAppendSize(null); // disable limit

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());
  }

  @Test
  public void getUploadedBytesShouldReturnInputStream() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(12L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("Hello World!".getBytes()));

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      assertEquals(
          "Hello World!", org.apache.commons.io.IOUtils.toString(is, StandardCharsets.UTF_8));
    }

    try (InputStream is =
        storageService.getUploadedBytes("/test/upload/" + created.getId(), "owner1")) {
      assertNotNull(is);
      assertEquals(
          "Hello World!", org.apache.commons.io.IOUtils.toString(is, StandardCharsets.UTF_8));
    }
  }

  @Test
  public void getUploadInfoShouldReturnInfo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");

    UploadInfo fetchedById = storageService.getUploadInfo(created.getId());
    assertNotNull(fetchedById);
    assertEquals(created.getId(), fetchedById.getId());

    UploadInfo fetchedByUri =
        storageService.getUploadInfo("/test/upload/" + created.getId(), "owner1");
    assertNotNull(fetchedByUri);
    assertEquals(created.getId(), fetchedByUri.getId());

    assertNull(storageService.getUploadInfo(new UploadId("non-existing-id")));
    assertNull(storageService.getUploadInfo("/test/upload/non-existing-id", "owner1"));
  }

  @Test
  public void copyUploadToShouldCopyDataToOutputStream() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(11L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("Hello Azure".getBytes()));

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storageService.copyUploadTo(created, baos);
    assertEquals("Hello Azure", baos.toString());

    ByteArrayOutputStream baosUri = new ByteArrayOutputStream();
    UploadInfo fetchedUri =
        storageService.getUploadInfo("/test/upload/" + created.getId(), "owner1");
    storageService.copyUploadTo(fetchedUri, baosUri);
    assertEquals("Hello Azure", baosUri.toString());
  }

  @Test
  public void terminateUploadShouldDeleteBlobs() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertNotNull(storageService.getUploadInfo(created.getId()));

    storageService.terminateUpload(created);
    assertNull(storageService.getUploadInfo(created.getId()));
  }

  @Test
  public void removeLastNumberOfBytesPartBlobOnly() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024);

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());

    // Remove 4 bytes from sub-threshold part blob
    storageService.removeLastNumberOfBytes(created, 4L);
    assertEquals(Long.valueOf(6L), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      assertEquals("012345", org.apache.commons.io.IOUtils.toString(is, StandardCharsets.UTF_8));
    }

    // Remove remaining bytes
    storageService.removeLastNumberOfBytes(created, 6L);
    assertEquals(Long.valueOf(0L), created.getOffset());
  }

  @Test
  public void removeLastNumberOfBytesTrimPartBlobWithCommittedBlocks() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB blocks

    byte[] data = new byte[9 * 1024 * 1024];
    java.util.Arrays.fill(data, (byte) 'B');

    UploadInfo info = new UploadInfo();
    info.setLength((long) data.length);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream(data));
    assertEquals(Long.valueOf(data.length), created.getOffset());

    // Truncate by 500KB (targetOffset 8.5MB > 8MB block blob size)
    storageService.removeLastNumberOfBytes(created, 500 * 1024);
    assertEquals(Long.valueOf(data.length - 500 * 1024), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      byte[] readBytes = org.apache.commons.io.IOUtils.toByteArray(is);
      assertEquals(data.length - 500 * 1024, readBytes.length);
    }
  }

  @Test
  public void appendMultiBlockPayloadAndTruncateCommittedBlocks() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB blocks

    byte[] data = new byte[9 * 1024 * 1024];
    java.util.Arrays.fill(data, (byte) 'A');

    UploadInfo info = new UploadInfo();
    info.setLength((long) data.length);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream(data));
    assertEquals(Long.valueOf(data.length), created.getOffset());

    // Truncate by 2MB (targetOffset 7MB <= 8MB block blob size)
    storageService.removeLastNumberOfBytes(created, 2L * 1024 * 1024);
    assertEquals(Long.valueOf(7L * 1024 * 1024), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
    }
  }

  @Test
  public void cleanupExpiredUploadsShouldDeleteExpired() throws Exception {
    storageService.setUploadExpirationPeriod(1L);

    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream("0123456789".getBytes()));

    Thread.sleep(50L);
    // Cleanup with expiration period 0 (all uploads expired)
    storageService.cleanupExpiredUploads(new AzureBlobLockingService(containerClient));
    assertNull(storageService.getUploadInfo(created.getId()));
  }

  @Test
  public void getUploadInfoByChecksumSelfCleaningStaleIndex() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);

    UploadInfo info = new UploadInfo();
    info.setLength(5L);
    info.setChecksum("5d41402abc4b2a76b9719d911017c592");
    info.setChecksumAlgorithm(ChecksumAlgorithm.MD5);

    UploadInfo created = storageService.create(info, "owner1");
    storageService.append(created, new ByteArrayInputStream("hello".getBytes()));

    UploadInfo found =
        storageService.getUploadInfoByChecksum(
            "5d41402abc4b2a76b9719d911017c592", ChecksumAlgorithm.MD5);
    assertNotNull(found);
    assertEquals(created.getId(), found.getId());

    // Manually delete upload blobs to simulate stale index
    storageService.terminateUpload(created);

    // Stale index lookup should self-clean and return null
    assertNull(
        storageService.getUploadInfoByChecksum(
            "5d41402abc4b2a76b9719d911017c592", ChecksumAlgorithm.MD5));
  }

  @Test
  public void testFullUploadLifecycleOnAzurite() throws Exception {
    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);
    AzureBlobLockingService locking = new AzureBlobLockingService(containerClient);

    UploadInfo info = new UploadInfo();
    info.setLength(11L);

    UploadInfo created = storage.create(info, "owner1");
    assertNotNull(created.getId());

    try (UploadLock lock = locking.lockUploadByUri("/test/upload/" + created.getId())) {
      assertNotNull(lock);
      storage.append(created, new ByteArrayInputStream("hello ".getBytes()));
      storage.append(created, new ByteArrayInputStream("world".getBytes()));
    }

    UploadInfo fetched = storage.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(11L), fetched.getOffset());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storage.copyUploadTo(fetched, baos);
    assertEquals("hello world", baos.toString());

    String blobName = storage.getAzureBlobName("/test/upload/" + created.getId(), "owner1");
    assertEquals("uploads/" + created.getId(), blobName);
  }

  @Test
  public void testTruncateBytesOnAzurite() throws Exception {
    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);

    UploadInfo info = new UploadInfo();
    info.setLength(10L);
    UploadInfo created = storage.create(info, "owner1");

    storage.append(created, new ByteArrayInputStream("0123456789".getBytes()));
    assertEquals(Long.valueOf(10L), created.getOffset());

    storage.removeLastNumberOfBytes(created, 3L);
    assertEquals(Long.valueOf(7L), created.getOffset());

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    storage.copyUploadTo(created, baos);
    assertEquals("0123456", baos.toString());
  }

  @Test
  public void testDeduplicationOnAzurite() throws Exception {
    AzureBlobStorageService storage = new AzureBlobStorageService(containerClient);
    storage.setUploadDeduplicationEnabled(true);

    UploadInfo parent = new UploadInfo();
    parent.setLength(5L);
    parent.setChecksum("5d41402abc4b2a76b9719d911017c592");
    parent.setChecksumAlgorithm(ChecksumAlgorithm.MD5);
    parent = storage.create(parent, "owner1");
    storage.append(parent, new ByteArrayInputStream("hello".getBytes()));

    UploadInfo child = new UploadInfo();
    child.setLength(5L);
    child.setChecksum("5d41402abc4b2a76b9719d911017c592");
    child.setChecksumAlgorithm(ChecksumAlgorithm.MD5);
    child = storage.create(child, "owner1");
    storage.append(child, new ByteArrayInputStream("hello".getBytes()));

    assertNotNull(child.getDuplicatesUploadId());
    assertEquals(parent.getId(), child.getDuplicatesUploadId());

    storage.terminateUpload(parent);
    assertNull(
        storage.getUploadInfoByChecksum("5d41402abc4b2a76b9719d911017c592", ChecksumAlgorithm.MD5));
  }

  @Test
  public void appendConsecutiveCommittedBlocksShouldRetrieveCommittedBlockIds() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB block size

    byte[] block1 = new byte[4 * 1024 * 1024];
    java.util.Arrays.fill(block1, (byte) 'X');

    byte[] block2 = new byte[4 * 1024 * 1024];
    java.util.Arrays.fill(block2, (byte) 'Y');

    UploadInfo info = new UploadInfo();
    info.setLength(10L * 1024 * 1024);
    UploadInfo created = storageService.create(info, "owner1");

    // 1st append commits block 1 (4MB)
    storageService.append(created, new ByteArrayInputStream(block1));
    assertEquals(Long.valueOf(4L * 1024 * 1024), created.getOffset());

    // 2nd append calls getCommittedBlockIds(blockBlobClient) and commits block 2 (4MB)
    storageService.append(created, new ByteArrayInputStream(block2));
    assertEquals(Long.valueOf(8L * 1024 * 1024), created.getOffset());

    // 3rd append calls getCommittedBlockIds(blockBlobClient) which retrieves 2 committed block IDs
    // from Azurite
    storageService.append(created, new ByteArrayInputStream("extra".getBytes()));
    assertEquals(Long.valueOf(8L * 1024 * 1024 + 5), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      byte[] readBytes = org.apache.commons.io.IOUtils.toByteArray(is);
      assertEquals(8 * 1024 * 1024 + 5, readBytes.length);
    }
  }
}
