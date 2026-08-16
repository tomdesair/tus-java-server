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
import me.desair.tus.server.upload.UuidUploadIdFactory;
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
  public void getUploadInfoShouldReturnNullWhenInfoBlobIsCorruptJson() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    UploadInfo created = storageService.create(info, "owner1");
    assertNotNull(created);

    // Overwrite the metadata .info blob with corrupted/invalid JSON data
    com.azure.storage.blob.BlobClient infoBlob =
        containerClient.getBlobClient("metadata/" + created.getId() + ".info");
    byte[] corruptBytes =
        "{invalid-json-structure: true, corrupted".getBytes(StandardCharsets.UTF_8);
    infoBlob.upload(new ByteArrayInputStream(corruptBytes), corruptBytes.length, true);

    // getUploadInfo should catch the deserialization failure and return null
    UploadInfo result = storageService.getUploadInfo(created.getId());
    assertNull(result);

    // Also verify getUploadInfo by URI and ownerKey returns null
    UploadInfo uriResult =
        storageService.getUploadInfo("/test/upload/" + created.getId(), "owner1");
    assertNull(uriResult);
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

  @Test
  public void testCleanupExpiredUploadsSkipsCurrentlyLockedUploads() throws Exception {
    UuidUploadIdFactory idFactory = new UuidUploadIdFactory();
    idFactory.setUploadUri("/test/upload");

    AzureBlobLockingService lockingService = new AzureBlobLockingService(containerClient);
    lockingService.setIdFactory(idFactory);
    storageService.setIdFactory(idFactory);

    UploadInfo expiredLocked = new UploadInfo();
    expiredLocked.setLength(100L);
    expiredLocked.setExpirationTimestamp(System.currentTimeMillis() - 10_000L);
    expiredLocked = storageService.create(expiredLocked, "owner1");

    UploadInfo expiredUnlocked = new UploadInfo();
    expiredUnlocked.setLength(100L);
    expiredUnlocked.setExpirationTimestamp(System.currentTimeMillis() - 10_000L);
    expiredUnlocked = storageService.create(expiredUnlocked, "owner1");

    UploadLock lock = lockingService.lockUploadByUri("/test/upload/" + expiredLocked.getId());
    assertNotNull(lock);

    try {
      storageService.cleanupExpiredUploads(lockingService);

      // Locked upload should NOT have been deleted
      assertNotNull(storageService.getUploadInfo(expiredLocked.getId()));

      // Unlocked upload SHOULD have been cleaned up
      assertNull(storageService.getUploadInfo(expiredUnlocked.getId()));
    } finally {
      lock.release();
    }
  }

  @Test
  public void testAutoCalibrationOptimalBlockSize() throws Exception {
    UploadInfo info = new UploadInfo();
    // 500 GB total length triggers auto-calibration scaling block size above preferred size
    info.setLength(500_000_000_000L);
    UploadInfo created = storageService.create(info, "owner1");

    byte[] chunk = "sample-chunk-data".getBytes(StandardCharsets.UTF_8);
    storageService.append(created, new ByteArrayInputStream(chunk));
    assertEquals(Long.valueOf(chunk.length), created.getOffset());
  }

  @Test
  public void testAppendMultiChunkWithSubThresholdRemainder() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB

    // 4.5 MB payload on 10MB upload: 4MB stages as block, 500KB buffers into .part
    byte[] data = new byte[(4 * 1024 + 500) * 1024];
    java.util.Arrays.fill(data, (byte) 'M');

    UploadInfo info = new UploadInfo();
    info.setLength(10L * 1024 * 1024);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream(data));
    assertEquals(Long.valueOf(data.length), created.getOffset());

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      byte[] readBytes = org.apache.commons.io.IOUtils.toByteArray(is);
      assertEquals(data.length, readBytes.length);
    }
  }

  @Test
  public void testTruncatePartBlobDownToZeroDeletesPartBlob() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB

    byte[] data = new byte[(4 * 1024 + 500) * 1024]; // 4.5MB
    java.util.Arrays.fill(data, (byte) 'T');

    UploadInfo info = new UploadInfo();
    info.setLength(10L * 1024 * 1024);
    UploadInfo created = storageService.create(info, "owner1");

    storageService.append(created, new ByteArrayInputStream(data));
    assertEquals(Long.valueOf(data.length), created.getOffset());

    // Truncate by exactly 500KB down to 4MB (block boundary)
    storageService.removeLastNumberOfBytes(created, 500 * 1024L);
    assertEquals(Long.valueOf(4L * 1024 * 1024), created.getOffset());

    // .part blob should be deleted
    com.azure.storage.blob.BlobClient partBlob =
        containerClient.getBlobClient("metadata/" + created.getId() + ".part");
    org.junit.Assert.assertFalse(partBlob.exists());
  }

  @Test
  public void testEmptyStreamFallbackForUnstartedUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    UploadInfo created = storageService.create(info, "owner1");

    try (InputStream is = storageService.getUploadedBytes(created.getId())) {
      assertNotNull(is);
      assertEquals(0, is.available());
    }
  }

  @Test
  public void testAppendInterruptedSubBlock() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    UploadInfo created = storageService.create(info, "owner1");

    byte[] validBytes = "12345678901234567890123456789012345678901234567890".getBytes(); // 50 bytes
    InputStream brokenStream = org.mockito.Mockito.mock(InputStream.class);
    org.mockito.Mockito.when(
            brokenStream.read(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new java.io.IOException("Stream interrupted"));

    InputStream sequenceStream =
        new java.io.SequenceInputStream(new ByteArrayInputStream(validBytes), brokenStream);

    try {
      storageService.append(created, sequenceStream);
      org.junit.Assert.fail("Expected IOException to be thrown");
    } catch (java.io.IOException e) {
      assertEquals("Stream interrupted", e.getMessage());
    }

    assertEquals(Long.valueOf(50L), created.getOffset());

    UploadInfo fetched = storageService.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(50L), fetched.getOffset());

    // Subsequent append resumes cleanly
    storageService.append(created, new ByteArrayInputStream("abcdefghij".getBytes()));
    assertEquals(Long.valueOf(60L), created.getOffset());
  }

  @Test
  public void testAppendInterruptedMultiBlock() throws Exception {
    storageService.setPreferredBlockSize(4L * 1024 * 1024); // 4MB

    UploadInfo info = new UploadInfo();
    info.setLength(20L * 1024 * 1024);
    UploadInfo created = storageService.create(info, "owner1");

    byte[] fourMb = new byte[4 * 1024 * 1024];
    java.util.Arrays.fill(fourMb, (byte) 'A');
    byte[] extraBytes = new byte[100];
    java.util.Arrays.fill(extraBytes, (byte) 'B');

    InputStream brokenStream = org.mockito.Mockito.mock(InputStream.class);
    org.mockito.Mockito.when(
            brokenStream.read(
                org.mockito.ArgumentMatchers.any(byte[].class),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new java.io.IOException("Stream interrupted on chunk 2"));

    InputStream combinedStream =
        new java.io.SequenceInputStream(
            new java.io.SequenceInputStream(
                new ByteArrayInputStream(fourMb), new ByteArrayInputStream(extraBytes)),
            brokenStream);

    try {
      storageService.append(created, combinedStream);
      org.junit.Assert.fail("Expected IOException to be thrown");
    } catch (java.io.IOException e) {
      assertEquals("Stream interrupted on chunk 2", e.getMessage());
    }

    assertEquals(Long.valueOf(4L * 1024 * 1024 + 100L), created.getOffset());

    UploadInfo fetched = storageService.getUploadInfo(created.getId());
    assertNotNull(fetched);
    assertEquals(Long.valueOf(4L * 1024 * 1024 + 100L), fetched.getOffset());
  }
}
