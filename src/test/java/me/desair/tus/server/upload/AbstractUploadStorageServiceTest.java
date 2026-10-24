package me.desair.tus.server.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.checksum.DeduplicationService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import me.desair.tus.server.util.Utils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Unit tests for {@link AbstractUploadStorageService}. */
public class AbstractUploadStorageServiceTest {

  private TestUploadStorageService storageService;
  private DeduplicationService mockDeduplicationService;
  private UploadLockingService mockLockingService;

  private static class TestUploadStorageService extends AbstractUploadStorageService {
    private DeduplicationService deduplicationService;
    private byte[] uploadedBytes = new byte[] {1, 2, 3};
    private UploadInfo storedUploadInfo;

    @Override
    public UploadInfo getUploadInfo(UploadId id) throws IOException {
      return storedUploadInfo;
    }

    @Override
    public String getUploadUri() {
      return "/files";
    }

    @Override
    public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
      return info;
    }

    @Override
    public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {}

    @Override
    public UploadInfo append(UploadInfo upload, InputStream inputStream)
        throws IOException, TusException {
      return upload;
    }

    @Override
    public InputStream getUploadedBytes(String uploadUri, String ownerKey)
        throws IOException, UploadNotFoundException {
      return uploadedBytes != null ? new ByteArrayInputStream(uploadedBytes) : null;
    }

    @Override
    public InputStream getUploadedBytes(UploadId id) throws IOException, UploadNotFoundException {
      return uploadedBytes != null ? new ByteArrayInputStream(uploadedBytes) : null;
    }

    @Override
    public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
        throws UploadNotFoundException, IOException {}

    @Override
    public void terminateUpload(UploadInfo uploadInfo)
        throws UploadNotFoundException, IOException {}

    @Override
    protected DeduplicationService getDeduplicationService() {
      return deduplicationService;
    }
  }

  @Before
  public void setUp() {
    storageService = new TestUploadStorageService();
    mockDeduplicationService = mock(DeduplicationService.class);
    mockLockingService = mock(UploadLockingService.class);
    storageService.deduplicationService = mockDeduplicationService;
  }

  @After
  public void tearDown() throws IOException {
    storageService.close();
  }

  @Test
  public void testMaxUploadSizeAccessors() {
    assertEquals(0L, storageService.getMaxUploadSize());
    storageService.setMaxUploadSize(500L);
    assertEquals(500L, storageService.getMaxUploadSize());
    storageService.setMaxUploadSize(-10L);
    assertEquals(0L, storageService.getMaxUploadSize());
    storageService.setMaxUploadSize(null);
    assertEquals(0L, storageService.getMaxUploadSize());
  }

  @Test
  public void testMaxAppendSizeAccessors() {
    assertNull(storageService.getMaxAppendSize());

    // When maxUploadSize is set and maxAppendSize is null, returns maxUploadSize
    storageService.setMaxUploadSize(1000L);
    assertEquals(Long.valueOf(1000L), storageService.getMaxAppendSize());

    // Explicit maxAppendSize takes precedence
    storageService.setMaxAppendSize(200L);
    assertEquals(Long.valueOf(200L), storageService.getMaxAppendSize());

    storageService.setMaxAppendSize(-1L);
    assertEquals(Long.valueOf(1000L), storageService.getMaxAppendSize());
  }

  @Test
  public void testMinAppendSizeAndMinSizeAccessors() {
    assertNull(storageService.getMinAppendSize());
    storageService.setMinAppendSize(100L);
    assertEquals(Long.valueOf(100L), storageService.getMinAppendSize());
    storageService.setMinAppendSize(-5L);
    assertNull(storageService.getMinAppendSize());

    assertNull(storageService.getMinSize());
    storageService.setMinSize(50L);
    assertEquals(Long.valueOf(50L), storageService.getMinSize());
    storageService.setMinSize(-1L);
    assertNull(storageService.getMinSize());
  }

  @Test
  public void testExpirationPeriodAndConcatenationService() {
    assertNull(storageService.getUploadExpirationPeriod());
    storageService.setUploadExpirationPeriod(60000L);
    assertEquals(Long.valueOf(60000L), storageService.getUploadExpirationPeriod());

    assertNull(storageService.getUploadConcatenationService());
    UploadConcatenationService mockConcat = mock(UploadConcatenationService.class);
    storageService.setUploadConcatenationService(mockConcat);
    assertEquals(mockConcat, storageService.getUploadConcatenationService());
  }

  @Test
  public void testDeduplicationEnabledAccessors() {
    assertFalse(storageService.isUploadDeduplicationEnabled());
    storageService.setUploadDeduplicationEnabled(true);
    assertTrue(storageService.isUploadDeduplicationEnabled());
  }

  @Test
  public void testLockingServiceAccessors() {
    assertNull(storageService.getUploadLockingService());
    storageService.setUploadLockingService(mockLockingService);
    assertEquals(mockLockingService, storageService.getUploadLockingService());
    verify(mockDeduplicationService).setUploadLockingService(mockLockingService);
  }

  @Test(expected = NullPointerException.class)
  public void testSetUploadLockingServiceNullThrowsNpe() {
    storageService.setUploadLockingService(null);
  }

  @Test
  public void testIdFactoryAndJsonSerialization() {
    assertNotNull(storageService.getIdFactory());
    UploadIdFactory mockFactory = mock(UploadIdFactory.class);
    storageService.setIdFactory(mockFactory);
    assertEquals(mockFactory, storageService.getIdFactory());
    storageService.setIdFactory(null);
    assertEquals(mockFactory, storageService.getIdFactory());

    assertTrue(storageService.isJsonSerializationEnabled());
    storageService.setJsonSerializationEnabled(false);
    assertFalse(storageService.isJsonSerializationEnabled());
  }

  @Test
  public void testDrainTimeout() {
    assertEquals(Duration.ofSeconds(10), storageService.getDrainTimeout());
    storageService.setDrainTimeout(Duration.ofSeconds(20));
    assertEquals(Duration.ofSeconds(20), storageService.getDrainTimeout());
    storageService.setDrainTimeout(null);
    assertEquals(Duration.ofSeconds(20), storageService.getDrainTimeout());
  }

  @Test
  public void testThreadPoolSizeAndExecutorLifecycle() {
    assertEquals(10, storageService.getCloudUploadThreadPoolSize());
    assertNull(storageService.getUploadExecutor());

    // Initialize executor
    storageService.uploadExecutor = Utils.createUploadExecutor(2, "abstract-pool");
    assertEquals(2, storageService.getUploadExecutor().getCorePoolSize());

    storageService.setCloudUploadThreadPoolSize(4);
    assertEquals(4, storageService.getCloudUploadThreadPoolSize());
    assertEquals(4, storageService.getUploadExecutor().getMaximumPoolSize());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSetCloudUploadThreadPoolSizeInvalidThrows() {
    storageService.setCloudUploadThreadPoolSize(0);
  }

  @Test
  public void testCopyUploadToSuccess() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-123"));
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    storageService.copyUploadTo(info, os);
    assertEquals(3, os.toByteArray().length);
  }

  @Test(expected = UploadNotFoundException.class)
  public void testCopyUploadToMissingBytesThrows() throws Exception {
    storageService.uploadedBytes = null;
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("missing"));
    storageService.copyUploadTo(info, new ByteArrayOutputStream());
  }

  @Test(expected = NullPointerException.class)
  public void testCopyUploadToNullInfoThrows() throws Exception {
    storageService.copyUploadTo(null, new ByteArrayOutputStream());
  }

  @Test(expected = NullPointerException.class)
  public void testCopyUploadToNullStreamThrows() throws Exception {
    storageService.copyUploadTo(new UploadInfo(), null);
  }

  @Test
  public void testGetUploadInfoWithUrlAndOwnerKey() throws Exception {
    UploadId id = new UploadId("abc-123");
    UploadInfo info = new UploadInfo();
    info.setId(id);
    info.setOwnerKey("owner-1");
    storageService.storedUploadInfo = info;

    UploadIdFactory mockFactory = mock(UploadIdFactory.class);
    when(mockFactory.readUploadId("/files/abc-123")).thenReturn(id);
    storageService.setIdFactory(mockFactory);

    // Matching owner
    assertEquals(info, storageService.getUploadInfo("/files/abc-123", "owner-1"));

    // Mismatched owner
    assertNull(storageService.getUploadInfo("/files/abc-123", "other-owner"));

    // Missing info
    storageService.storedUploadInfo = null;
    assertNull(storageService.getUploadInfo("/files/abc-123", "owner-1"));
  }

  @Test
  public void testDeduplicate() throws Exception {
    UploadInfo info = new UploadInfo();
    ChecksumKey key = ChecksumKey.of("0123456789abcdef", ChecksumAlgorithm.SHA256);

    // Deduplication disabled: returns original info
    storageService.setUploadDeduplicationEnabled(false);
    assertEquals(info, storageService.deduplicate(info, key));

    // Null inputs
    storageService.setUploadDeduplicationEnabled(true);
    assertNull(storageService.deduplicate(null, key));
    assertEquals(info, storageService.deduplicate(info, null));

    // Delegated to deduplicationService
    UploadInfo deduplicatedInfo = new UploadInfo();
    when(mockDeduplicationService.deduplicate(info, key)).thenReturn(deduplicatedInfo);
    assertEquals(deduplicatedInfo, storageService.deduplicate(info, key));

    // When deduplicationService is null
    storageService.deduplicationService = null;
    assertEquals(info, storageService.deduplicate(info, key));
  }

  @Test
  public void testGetUploadInfoByChecksum() throws Exception {
    // Disabled
    storageService.setUploadDeduplicationEnabled(false);
    assertNull(
        storageService.getUploadInfoByChecksum("0123456789abcdef", ChecksumAlgorithm.SHA256));

    storageService.setUploadDeduplicationEnabled(true);
    assertNull(storageService.getUploadInfoByChecksum(null, ChecksumAlgorithm.SHA256));
    assertNull(storageService.getUploadInfoByChecksum("0123456789abcdef", null));

    // Valid lookup
    ChecksumKey key = ChecksumKey.of("0123456789abcdef", ChecksumAlgorithm.SHA256);
    UploadInfo parent = new UploadInfo();
    when(mockDeduplicationService.getUploadInfoByChecksum(key)).thenReturn(parent);
    assertEquals(
        parent,
        storageService.getUploadInfoByChecksum("0123456789abcdef", ChecksumAlgorithm.SHA256));

    // Malformed checksum format returning null
    assertNull(
        storageService.getUploadInfoByChecksum("invalid-checksum!", ChecksumAlgorithm.SHA256));
  }

  @Test(expected = IOException.class)
  public void testGetUploadInfoByChecksumUnsafePathThrows() throws Exception {
    storageService.setUploadDeduplicationEnabled(true);
    storageService.getUploadInfoByChecksum(" / ", ChecksumAlgorithm.SHA256);
  }
}
