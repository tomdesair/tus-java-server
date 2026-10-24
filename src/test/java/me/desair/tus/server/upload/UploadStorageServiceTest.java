package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.checksum.ChecksumKey;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.concatenation.UploadConcatenationService;
import org.junit.Before;
import org.junit.Test;

public class UploadStorageServiceTest {

  private UploadStorageService dummyStorageService;

  @Before
  public void setUp() {
    dummyStorageService =
        new UploadStorageService() {
          @Override
          public UploadInfo getUploadInfo(String uploadUrl, String ownerKey) throws IOException {
            return null;
          }

          @Override
          public UploadInfo getUploadInfo(UploadId id) throws IOException {
            return null;
          }

          @Override
          public String getUploadUri() {
            return null;
          }

          @Override
          public UploadInfo append(UploadInfo upload, InputStream inputStream)
              throws IOException, TusException {
            return null;
          }

          @Override
          public void setMaxUploadSize(Long maxUploadSize) {}

          @Override
          public long getMaxUploadSize() {
            return 0;
          }

          @Override
          public UploadInfo create(UploadInfo info, String ownerKey) throws IOException {
            return null;
          }

          @Override
          public void update(UploadInfo uploadInfo) throws IOException, UploadNotFoundException {}

          @Override
          public InputStream getUploadedBytes(String uploadUri, String ownerKey)
              throws IOException, UploadNotFoundException {
            return null;
          }

          @Override
          public InputStream getUploadedBytes(UploadId id)
              throws IOException, UploadNotFoundException {
            if (id != null && "found".equals(id.toString())) {
              return new java.io.ByteArrayInputStream(new byte[] {1, 2, 3});
            }
            return null;
          }

          @Override
          public void removeLastNumberOfBytes(UploadInfo uploadInfo, long byteCount)
              throws UploadNotFoundException, IOException {}

          @Override
          public void terminateUpload(UploadInfo uploadInfo)
              throws UploadNotFoundException, IOException {}

          @Override
          public Long getUploadExpirationPeriod() {
            return null;
          }

          @Override
          public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {}

          @Override
          public void setUploadConcatenationService(
              UploadConcatenationService concatenationService) {}

          @Override
          public UploadConcatenationService getUploadConcatenationService() {
            return null;
          }

          @Override
          public void setIdFactory(UploadIdFactory idFactory) {}
        };
  }

  @Test
  public void testDefaultSetUploadDeduplicationEnabled() {
    // Should do nothing without exception
    dummyStorageService.setUploadDeduplicationEnabled(true);
    dummyStorageService.setUploadDeduplicationEnabled(false);
  }

  @Test
  public void testDefaultIsUploadDeduplicationEnabled() {
    assertThat(dummyStorageService.isUploadDeduplicationEnabled(), is(false));
  }

  @Test
  public void testDefaultCopyUploadTo() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("found"));
    java.io.ByteArrayOutputStream os = new java.io.ByteArrayOutputStream();
    dummyStorageService.copyUploadTo(info, os);
    org.junit.Assert.assertArrayEquals(new byte[] {1, 2, 3}, os.toByteArray());
  }

  @Test(expected = UploadNotFoundException.class)
  public void testDefaultCopyUploadToNotFound() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("missing"));
    dummyStorageService.copyUploadTo(info, new java.io.ByteArrayOutputStream());
  }

  @Test(expected = NullPointerException.class)
  public void testDefaultCopyUploadToNullInfo() throws Exception {
    dummyStorageService.copyUploadTo(null, new java.io.ByteArrayOutputStream());
  }

  @Test(expected = NullPointerException.class)
  public void testDefaultCopyUploadToNullOutputStream() throws Exception {
    dummyStorageService.copyUploadTo(new UploadInfo(), null);
  }

  @Test
  public void testDefaultGetUploadInfoByChecksum() throws IOException {
    assertThat(
        dummyStorageService.getUploadInfoByChecksum("some-checksum", ChecksumAlgorithm.SHA256),
        is(nullValue()));
  }

  @Test
  public void testDefaultAppendAndSizeConfigurationMethods() throws Exception {
    dummyStorageService.setMaxAppendSize(1000L);
    assertThat(dummyStorageService.getMaxAppendSize(), is(nullValue()));

    dummyStorageService.setMinAppendSize(100L);
    assertThat(dummyStorageService.getMinAppendSize(), is(nullValue()));

    dummyStorageService.setMinSize(50L);
    assertThat(dummyStorageService.getMinSize(), is(nullValue()));

    dummyStorageService.setJsonSerializationEnabled(true);
    assertThat(dummyStorageService.isJsonSerializationEnabled(), is(false));

    dummyStorageService.setCloudUploadThreadPoolSize(15);
    assertThat(dummyStorageService.getCloudUploadThreadPoolSize(), is(10));

    dummyStorageService.setDrainTimeout(Duration.ofSeconds(20));
    assertThat(dummyStorageService.getDrainTimeout(), is(Duration.ofSeconds(55)));

    // Default close is a no-op
    dummyStorageService.close();
  }

  @Test
  public void testDefaultDeduplicateAndLockingService() throws Exception {
    UploadInfo info = new UploadInfo();
    ChecksumKey key =
        ChecksumKey.of("0123456789abcdef0123456789abcdef01234567", ChecksumAlgorithm.SHA1);
    assertThat(dummyStorageService.deduplicate(info, key), is(info));
    dummyStorageService.setUploadLockingService(new NoOpUploadLockingService());
    assertThat(dummyStorageService.getUploadLockingService(), is(nullValue()));
  }

  @Test(expected = NullPointerException.class)
  public void testSetUploadLockingServiceThrowsOnNull() {
    dummyStorageService.setUploadLockingService(null);
  }

  @Test
  public void testDefaultDataExistsAndDeleteData() throws Exception {
    UploadId id = new UploadId("test-id");
    assertThat(dummyStorageService.dataExists(id), is(false));

    // KISS: verifying method executes cleanly without throwing an exception
    dummyStorageService.deleteData(id);
  }

  @Test
  public void testDefaultCleanupExpiredUploads() throws Exception {
    // KISS: verifying method executes cleanly without throwing an exception
    dummyStorageService.cleanupExpiredUploads();
    dummyStorageService.cleanupExpiredUploads(null);

    UploadLockingService mockLocking = org.mockito.Mockito.mock(UploadLockingService.class);
    dummyStorageService.cleanupExpiredUploads(mockLocking);
  }

  @Test
  public void testDefaultUploadConcatenationServiceClose() throws Exception {
    UploadConcatenationService concatService =
        new UploadConcatenationService() {
          @Override
          public void merge(UploadInfo uploadInfo) {}

          @Override
          public InputStream getConcatenatedBytes(UploadInfo uploadInfo) {
            return null;
          }

          @Override
          public java.util.List<UploadInfo> getPartialUploads(UploadInfo info) {
            return null;
          }
        };
    // KISS: verifying default close() executes cleanly without throwing an exception
    concatService.close();
  }
}
