package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.disk.DiskStorageService;
import me.desair.tus.server.util.AbstractTusExtension;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Test;

public class CoverageGapTest {

  private static class TestTusExtensionDirect implements TusExtension {
    boolean validateCalled = false;
    boolean processCalled = false;
    boolean handleErrorCalled = false;

    @Override
    public String getName() {
      return "test";
    }

    @Override
    public java.util.Collection<HttpMethod> getMinimalSupportedHttpMethods() {
      return java.util.Collections.emptyList();
    }

    @Override
    public void validate(
        HttpMethod method,
        HttpServletRequest servletRequest,
        UploadStorageService uploadStorageService,
        String ownerKey)
        throws TusException, IOException {
      validateCalled = true;
    }

    @Override
    public void process(
        HttpMethod method,
        TusServletRequest servletRequest,
        TusServletResponse servletResponse,
        UploadStorageService uploadStorageService,
        String ownerKey)
        throws IOException, TusException {
      processCalled = true;
    }

    @Override
    public void handleError(
        HttpMethod method,
        TusServletRequest servletRequest,
        TusServletResponse servletResponse,
        UploadStorageService uploadStorageService,
        String ownerKey)
        throws IOException, TusException {
      handleErrorCalled = true;
    }
  }

  private static class DummyAbstractExtension extends AbstractTusExtension {
    boolean handleError7Called = false;

    @Override
    public String getName() {
      return "dummy";
    }

    @Override
    public java.util.Collection<HttpMethod> getMinimalSupportedHttpMethods() {
      return java.util.Collections.emptyList();
    }

    @Override
    protected void initValidators(java.util.List<RequestValidator> requestValidators) {}

    @Override
    protected void initRequestHandlers(java.util.List<RequestHandler> requestHandlers) {}

    @Override
    public void handleError(
        HttpMethod method,
        TusServletRequest request,
        TusServletResponse response,
        UploadStorageService uploadStorageService,
        UploadLockingService uploadLockingService,
        String ownerKey,
        ProtocolVersion version)
        throws IOException, TusException {
      handleError7Called = true;
    }
  }

  @Test
  public void testTusExtensionDefaultMethods() throws Exception {
    TestTusExtensionDirect extension = new TestTusExtensionDirect();

    extension.validate(HttpMethod.POST, null, null, null, null, ProtocolVersion.TUS_1_0_0);
    assertThat(extension.validateCalled, is(true));

    extension.process(HttpMethod.POST, null, null, null, null, null, ProtocolVersion.TUS_1_0_0);
    assertThat(extension.processCalled, is(true));

    extension.handleError(HttpMethod.POST, null, null, null, null, null, ProtocolVersion.TUS_1_0_0);
    assertThat(extension.handleErrorCalled, is(true));
  }

  @Test
  public void testAbstractTusExtensionHandleError5Args() throws Exception {
    DummyAbstractExtension ext = new DummyAbstractExtension();
    ext.handleError(HttpMethod.POST, null, null, null, null);
    assertThat(ext.handleError7Called, is(true));
  }

  @Test
  public void testTusFileUploadServiceWithMaxAppendSizeNull() {
    TusFileUploadService service = new TusFileUploadService();
    service.withMaxAppendSize(null);
    assertThat(service.detectProtocolVersion(null), is(ProtocolVersion.TUS_1_0_0));
  }

  @Test
  public void testUploadStorageServiceDefaultMaxAppendSize() {
    UploadStorageService storage =
        new UploadStorageService() {
          @Override
          public me.desair.tus.server.upload.UploadInfo getUploadInfo(
              String uploadUrl, String ownerKey) {
            return null;
          }

          @Override
          public me.desair.tus.server.upload.UploadInfo getUploadInfo(
              me.desair.tus.server.upload.UploadId id) {
            return null;
          }

          @Override
          public String getUploadUri() {
            return "/";
          }

          @Override
          public me.desair.tus.server.upload.UploadInfo append(
              me.desair.tus.server.upload.UploadInfo upload, java.io.InputStream inputStream) {
            return null;
          }

          @Override
          public void setMaxUploadSize(Long maxUploadSize) {}

          @Override
          public long getMaxUploadSize() {
            return 0;
          }

          @Override
          public me.desair.tus.server.upload.UploadInfo create(
              me.desair.tus.server.upload.UploadInfo info, String ownerKey) {
            return null;
          }

          @Override
          public void update(me.desair.tus.server.upload.UploadInfo uploadInfo) {}

          @Override
          public java.io.InputStream getUploadedBytes(String uploadUri, String ownerKey) {
            return null;
          }

          @Override
          public java.io.InputStream getUploadedBytes(me.desair.tus.server.upload.UploadId id) {
            return null;
          }

          @Override
          public void copyUploadTo(
              me.desair.tus.server.upload.UploadInfo info, java.io.OutputStream outputStream) {}

          @Override
          public void cleanupExpiredUploads(UploadLockingService uploadLockingService) {}

          @Override
          public void removeLastNumberOfBytes(
              me.desair.tus.server.upload.UploadInfo uploadInfo, long byteCount) {}

          @Override
          public void terminateUpload(me.desair.tus.server.upload.UploadInfo uploadInfo) {}

          @Override
          public Long getUploadExpirationPeriod() {
            return null;
          }

          @Override
          public void setUploadExpirationPeriod(Long uploadExpirationPeriod) {}

          @Override
          public void setUploadConcatenationService(
              me.desair.tus.server.upload.concatenation.UploadConcatenationService
                  concatenationService) {}

          @Override
          public me.desair.tus.server.upload.concatenation.UploadConcatenationService
              getUploadConcatenationService() {
            return null;
          }

          @Override
          public void setIdFactory(me.desair.tus.server.upload.UploadIdFactory idFactory) {}
        };

    assertThat(storage.getMaxAppendSize(), nullValue());
  }

  @Test
  public void testDiskStorageServiceSetAndGetMaxAppendSize() {
    DiskStorageService storage = new DiskStorageService("/tmp");
    storage.setMaxAppendSize(0L);
    assertThat(storage.getMaxAppendSize(), nullValue());

    storage.setMaxAppendSize(-5L);
    assertThat(storage.getMaxAppendSize(), nullValue());

    storage.setMaxUploadSize(500L);
    assertThat(storage.getMaxAppendSize(), is(500L));

    storage.setMaxAppendSize(200L);
    assertThat(storage.getMaxAppendSize(), is(200L));
  }
}
