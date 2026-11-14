package me.desair.tus.server.upload.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.UUID;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import org.junit.Before;
import org.junit.Test;

public class ThreadLocalCachedStorageAndLockingServiceTest {

  private UploadStorageService mockStorage;
  private UploadLockingService mockLocking;
  private ThreadLocalCachedStorageAndLockingService service;
  private UploadIdFactory mockIdFactory;

  @Before
  public void setUp() {
    mockStorage = mock(UploadStorageService.class);
    mockLocking = mock(UploadLockingService.class);
    service = new ThreadLocalCachedStorageAndLockingService(mockStorage, mockLocking);
    mockIdFactory = mock(UploadIdFactory.class);
    service.setIdFactory(mockIdFactory);
  }

  @Test
  public void testConstructorUnwrapping() {
    ThreadLocalCachedStorageAndLockingService outer =
        new ThreadLocalCachedStorageAndLockingService(service, service);
    assertNotNull(outer);
  }

  @Test
  public void testGetUploadInfoAndCaching() throws IOException {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);

    when(mockStorage.getUploadInfo(id)).thenReturn(info);

    UploadInfo res1 = service.getUploadInfo(id);
    assertEquals(info, res1);
    verify(mockStorage, times(1)).getUploadInfo(id);

    UploadInfo res2 = service.getUploadInfo(id);
    assertEquals(info, res2);
    verify(mockStorage, times(1)).getUploadInfo(id);
  }

  @Test
  public void testGetUploadInfoWithNullId() throws IOException {
    org.junit.Assert.assertNull(service.getUploadInfo((UploadId) null));
  }

  @Test
  public void testGetUploadInfoByUrlAndCaching() throws IOException {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);
    info.setOwnerKey("owner");

    when(mockIdFactory.readUploadId("/files/1")).thenReturn(id);
    when(mockStorage.getUploadInfo(id)).thenReturn(info);

    UploadInfo res1 = service.getUploadInfo("/files/1", "owner");
    assertEquals(info, res1);

    UploadInfo res2 = service.getUploadInfo("/files/1", "owner");
    assertEquals(info, res2);

    when(mockStorage.getUploadInfo("/files/1", "other")).thenReturn(info);
    service.getUploadInfo("/files/1", "other");
    verify(mockStorage, times(1)).getUploadInfo("/files/1", "other");
  }

  @Test
  public void testUpdateAndCache() throws Exception {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);

    service.update(info);
    verify(mockStorage, times(1)).update(info);

    UploadInfo res = service.getUploadInfo(id);
    assertEquals(info, res);
    verify(mockStorage, times(0)).getUploadInfo(id);
  }

  @Test
  public void testAppendAndCache() throws Exception {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);
    InputStream is = new ByteArrayInputStream(new byte[0]);

    when(mockStorage.append(info, is)).thenReturn(info);

    UploadInfo res = service.append(info, is);
    assertEquals(info, res);

    UploadInfo res2 = service.getUploadInfo(id);
    assertEquals(info, res2);
    verify(mockStorage, times(0)).getUploadInfo(id);
  }

  @Test
  public void testLockUploadAndClearCache() throws Exception {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);
    when(mockStorage.getUploadInfo(id)).thenReturn(info);

    UploadLock mockLock = mock(UploadLock.class);
    when(mockLocking.lockUploadByUri("/files/1")).thenReturn(mockLock);
    when(mockIdFactory.readUploadId("/files/1")).thenReturn(id);

    // Pre-lock read populates the ThreadLocal cache
    assertSame(info, service.getUploadInfo(id));

    UploadLock lock = service.lockUploadByUri("/files/1");
    assertNotNull(lock);
    lock.getUploadUri();
    verify(mockLock).getUploadUri();

    // First read inside the lock must re-fetch from storage because pre-lock cache was evicted;
    // second read inside the same lock hits the cache without calling mockStorage again.
    assertSame(info, service.getUploadInfo(id));
    assertSame(info, service.getUploadInfo(id));

    lock.release();
    verify(mockLock, times(1)).release();

    lock.close();
    verify(mockLock, times(1)).close();

    assertSame(info, service.getUploadInfo(id));
    verify(mockStorage, times(3)).getUploadInfo(id);

    // Acquiring a lock by UploadId must also evict any prior ThreadLocal cached entry
    when(mockLocking.lockUpload(id)).thenReturn(mockLock);
    try (UploadLock idLock = service.lockUpload(id)) {
      assertNotNull(idLock);
      assertSame(info, service.getUploadInfo(id));
    }
    verify(mockStorage, times(4)).getUploadInfo(id);
  }

  @Test
  public void testDelegateMethods() throws Exception {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);

    service.setMaxUploadSize(100L);
    verify(mockStorage, times(1)).setMaxUploadSize(100L);

    assertEquals(0, service.getMaxUploadSize());
    verify(mockStorage, times(1)).getMaxUploadSize();

    service.setMaxAppendSize(500L);
    verify(mockStorage, times(1)).setMaxAppendSize(500L);

    service.getMaxAppendSize();
    verify(mockStorage, times(1)).getMaxAppendSize();

    service.setMinAppendSize(200L);
    verify(mockStorage, times(1)).setMinAppendSize(200L);

    service.getMinAppendSize();
    verify(mockStorage, times(1)).getMinAppendSize();

    service.setMinSize(300L);
    verify(mockStorage, times(1)).setMinSize(300L);

    service.getMinSize();
    verify(mockStorage, times(1)).getMinSize();

    service.setCloudUploadThreadPoolSize(12);
    verify(mockStorage, times(1)).setCloudUploadThreadPoolSize(12);

    service.getCloudUploadThreadPoolSize();
    verify(mockStorage, times(1)).getCloudUploadThreadPoolSize();

    service.setDrainTimeout(Duration.ofSeconds(25));
    verify(mockStorage, times(1)).setDrainTimeout(Duration.ofSeconds(25));

    service.getDrainTimeout();
    verify(mockStorage, times(1)).getDrainTimeout();

    when(mockStorage.create(info, "owner")).thenReturn(info);
    assertEquals(info, service.create(info, "owner"));
    verify(mockStorage, times(1)).create(info, "owner");

    service.getUploadedBytes(id);
    verify(mockStorage, times(1)).getUploadedBytes(id);

    service.getUploadedBytes("/files/1", "owner");
    verify(mockStorage, times(1)).getUploadedBytes("/files/1", "owner");

    service.copyUploadTo(info, mock(OutputStream.class));
    verify(mockStorage, times(1)).copyUploadTo(any(), any());

    service.cleanupExpiredUploads();
    verify(mockStorage, times(1)).cleanupExpiredUploads();

    service.cleanupExpiredUploads(mockLocking);
    verify(mockStorage, times(1)).cleanupExpiredUploads(mockLocking);

    service.removeLastNumberOfBytes(info, 10L);
    verify(mockStorage, times(1)).removeLastNumberOfBytes(info, 10L);

    service.terminateUpload(info);
    verify(mockStorage, times(1)).terminateUpload(info);

    service.getUploadExpirationPeriod();
    verify(mockStorage, times(1)).getUploadExpirationPeriod();

    service.setUploadExpirationPeriod(100L);
    verify(mockStorage, times(1)).setUploadExpirationPeriod(100L);

    UploadConcatenationService mockConcat = mock(UploadConcatenationService.class);
    service.setUploadConcatenationService(mockConcat);
    verify(mockStorage, times(1)).setUploadConcatenationService(mockConcat);

    service.getUploadConcatenationService();
    verify(mockStorage, times(1)).getUploadConcatenationService();

    service.setUploadDeduplicationEnabled(true);
    verify(mockStorage, times(1)).setUploadDeduplicationEnabled(true);

    service.isUploadDeduplicationEnabled();
    verify(mockStorage, times(1)).isUploadDeduplicationEnabled();

    service.getUploadInfoByChecksum("sum", ChecksumAlgorithm.SHA1);
    verify(mockStorage, times(1)).getUploadInfoByChecksum("sum", ChecksumAlgorithm.SHA1);

    service.cleanupStaleLocks();
    verify(mockLocking, times(1)).cleanupStaleLocks();

    service.isLocked(id);
    verify(mockLocking, times(1)).isLocked(id);

    service.getUploadUri();
    verify(mockStorage, times(1)).getUploadUri();

    InputStream is = new ByteArrayInputStream(new byte[0]);
    service.registerInputStream("/files/1", is);
    verify(mockLocking, times(1)).registerInputStream("/files/1", is);

    service.unregisterInputStream("/files/1");
    verify(mockLocking, times(1)).unregisterInputStream("/files/1");

    service.requestLockRelease("/files/1");
    verify(mockLocking, times(1)).requestLockRelease("/files/1");

    service.getUploadLockingService();
    verify(mockStorage, times(1)).getUploadLockingService();

    service.dataExists(id);
    verify(mockStorage, times(1)).dataExists(id);

    service.deleteData(id);
    verify(mockStorage, times(1)).deleteData(id);

    service.lockUpload(id);
    verify(mockLocking, times(1)).lockUpload(id);

    service.lockUploadByUri("/files/1");
    verify(mockLocking, times(1)).lockUploadByUri("/files/1");

    service.close();
    verify(mockLocking, times(1)).close();
  }

  @Test
  public void testCleanupLockDelegates() throws IOException {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    service.cleanupLock(id);
    verify(mockLocking, times(1)).cleanupLock(id);
  }

  @Test
  public void testDelegateGettersReturnRawDelegates() throws IOException {
    assertSame(mockStorage, service.getStorageServiceDelegate());
    assertSame(mockLocking, service.getLockingServiceDelegate());

    ThreadLocalCachedStorageAndLockingService noFactoryService =
        new ThreadLocalCachedStorageAndLockingService(mockStorage, mockLocking);
    UploadInfo info = new UploadInfo();
    when(mockStorage.getUploadInfo("/files/no-factory", "owner")).thenReturn(info);
    assertSame(info, noFactoryService.getUploadInfo("/files/no-factory", "owner"));
  }

  @Test
  public void testCacheEvictedOnWriteExceptionsAndCloseGuaranteesBothDelegatesClosed()
      throws Exception {
    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo info = new UploadInfo();
    info.setId(id);
    info.setOffset(0L);
    info.setLength(100L);

    when(mockStorage.getUploadInfo(id)).thenReturn(info);
    // Warm the cache once; subsequent write errors must evict it so the delegate is queried again
    service.getUploadInfo(id);

    doThrow(new IOException("update failed")).when(mockStorage).update(info);
    assertThrows(IOException.class, () -> service.update(info));
    service.getUploadInfo(id);

    when(mockStorage.append(any(), any())).thenThrow(new IOException("append failed"));
    assertThrows(
        IOException.class, () -> service.append(info, new ByteArrayInputStream(new byte[1])));
    service.getUploadInfo(id);

    doThrow(new IOException("truncate failed")).when(mockStorage).removeLastNumberOfBytes(info, 1L);
    assertThrows(IOException.class, () -> service.removeLastNumberOfBytes(info, 1L));
    service.getUploadInfo(id);

    when(mockStorage.deduplicate(any(), any())).thenThrow(new IOException("dedup failed"));
    assertThrows(IOException.class, () -> service.deduplicate(info, null));
    service.getUploadInfo(id);

    // 1 initial load + 4 cache misses after each exception = 5 delegate reads
    verify(mockStorage, times(5)).getUploadInfo(id);

    doThrow(new IOException("locking close failed")).when(mockLocking).close();
    assertThrows(IOException.class, () -> service.close());
    verify(mockStorage, times(1)).close();
  }

  @Test
  public void testJsonSerializationDeduplicateSetLockingAndNullDelegateLock() throws Exception {
    service.setJsonSerializationEnabled(true);
    verify(mockStorage, times(1)).setJsonSerializationEnabled(true);

    when(mockStorage.isJsonSerializationEnabled()).thenReturn(true);
    assertTrue(service.isJsonSerializationEnabled());

    service.setUploadLockingService(mockLocking);
    verify(mockStorage, times(1)).setUploadLockingService(mockLocking);

    UploadId id = new UploadId(UUID.randomUUID().toString());
    UploadInfo input = new UploadInfo();
    input.setId(id);
    UploadInfo deduped = new UploadInfo();
    deduped.setId(id);
    deduped.setDuplicatesUploadId(new UploadId(UUID.randomUUID().toString()));
    ChecksumKey key = ChecksumKey.of("abcdef", ChecksumAlgorithm.SHA256);

    when(mockStorage.deduplicate(input, key)).thenReturn(deduped).thenReturn(null);
    assertSame(deduped, service.deduplicate(input, key));
    // Non-null deduplication result must populate the ThreadLocal cache
    assertSame(deduped, service.getUploadInfo(id));
    verify(mockStorage, never()).getUploadInfo(id);

    assertNull(service.deduplicate(input, key));

    ThreadLocalCachedStorageAndLockingService.CachedLock nullDelegateLock =
        service.new CachedLock(null);
    assertNull(nullDelegateLock.getUploadUri());
    nullDelegateLock.release();
    nullDelegateLock.close();
  }
}
