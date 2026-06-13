package me.desair.tus.server.upload.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
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

    service.getUploadInfo(id);

    UploadLock lock = service.lockUploadByUri("/files/1");
    assertNotNull(lock);

    lock.close();
    verify(mockLock, times(1)).close();

    service.getUploadInfo(id);
    verify(mockStorage, times(2)).getUploadInfo(id);
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

    when(mockStorage.create(info, "owner")).thenReturn(info);
    assertEquals(info, service.create(info, "owner"));
    verify(mockStorage, times(1)).create(info, "owner");

    service.getUploadedBytes(id);
    verify(mockStorage, times(1)).getUploadedBytes(id);

    service.getUploadedBytes("/files/1", "owner");
    verify(mockStorage, times(1)).getUploadedBytes("/files/1", "owner");

    service.copyUploadTo(info, mock(OutputStream.class));
    verify(mockStorage, times(1)).copyUploadTo(any(), any());

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

    service.requestLockRelease("/files/1");
    verify(mockLocking, times(1)).requestLockRelease("/files/1");
  }
}
