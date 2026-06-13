package me.desair.tus.server.upload;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
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
            return null;
          }

          @Override
          public void copyUploadTo(UploadInfo info, OutputStream outputStream)
              throws UploadNotFoundException, IOException {}

          @Override
          public void cleanupExpiredUploads(UploadLockingService uploadLockingService)
              throws IOException {}

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
  public void testDefaultGetUploadInfoByChecksum() throws IOException {
    assertThat(
        dummyStorageService.getUploadInfoByChecksum("some-checksum", ChecksumAlgorithm.SHA256),
        is(nullValue()));
  }
}
