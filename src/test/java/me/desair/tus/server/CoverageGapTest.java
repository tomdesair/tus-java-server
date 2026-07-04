package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.containsString;
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
    boolean handleError8Called = false;

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
    public HttpProblemDetails handleError(
        HttpMethod method,
        TusServletRequest request,
        TusServletResponse response,
        UploadStorageService uploadStorageService,
        UploadLockingService uploadLockingService,
        String ownerKey,
        ProtocolVersion version,
        TusException exception)
        throws IOException, TusException {
      handleError8Called = true;
      return null;
    }
  }

  private static class DummyAbstractExtensionWithProblemDetails extends AbstractTusExtension {
    DummyAbstractExtensionWithProblemDetails() {
      super();
    }

    @Override
    public String getName() {
      return "dummy-pd";
    }

    @Override
    public java.util.Collection<HttpMethod> getMinimalSupportedHttpMethods() {
      return java.util.Collections.emptyList();
    }

    @Override
    protected void initValidators(java.util.List<RequestValidator> requestValidators) {}

    @Override
    protected void initRequestHandlers(java.util.List<RequestHandler> requestHandlers) {
      requestHandlers.add(
          new RequestHandler() {
            @Override
            public boolean supports(HttpMethod method) {
              return true;
            }

            @Override
            public void process(
                HttpMethod method,
                TusServletRequest servletRequest,
                TusServletResponse servletResponse,
                UploadStorageService uploadStorageService,
                String ownerKey)
                throws java.io.IOException, TusException {}

            @Override
            public boolean isErrorHandler() {
              return true;
            }

            @Override
            public HttpProblemDetails process(
                HttpMethod method,
                TusServletRequest servletRequest,
                TusServletResponse servletResponse,
                UploadStorageService uploadStorageService,
                UploadLockingService lockingService,
                String ownerKey,
                TusException exception)
                throws java.io.IOException, TusException {
              return HttpProblemDetails.forCompletedUpload(400);
            }
          });
    }
  }

  @Test
  public void testTusExtensionDefaultMethods() throws Exception {
    TestTusExtensionDirect extension = new TestTusExtensionDirect();

    extension.validate(HttpMethod.POST, null, null, null, null, ProtocolVersion.TUS_1_0_0);
    assertThat(extension.validateCalled, is(true));

    extension.process(HttpMethod.POST, null, null, null, null, null, ProtocolVersion.TUS_1_0_0);
    assertThat(extension.processCalled, is(true));

    extension.handleError(
        HttpMethod.POST, null, null, null, null, null, ProtocolVersion.TUS_1_0_0, null);
    assertThat(extension.handleErrorCalled, is(true));

    extension.handleErrorCalled = false;
    HttpProblemDetails pd =
        extension.handleError(
            HttpMethod.POST,
            null,
            null,
            null,
            null,
            null,
            ProtocolVersion.TUS_1_0_0,
            new TusException(400, "Error"));
    assertThat(extension.handleErrorCalled, is(true));
    assertThat(pd, nullValue());
  }

  @Test
  public void testAbstractTusExtensionHandleError5Args() throws Exception {
    DummyAbstractExtension ext = new DummyAbstractExtension();
    ext.handleError(HttpMethod.POST, null, null, null, null);
    assertThat(ext.handleError8Called, is(true));
  }

  @Test
  public void testAbstractTusExtensionHandleError5ArgsWithProblemDetails() throws Exception {
    DummyAbstractExtensionWithProblemDetails ext = new DummyAbstractExtensionWithProblemDetails();
    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();
    ext.handleError(
        HttpMethod.POST,
        new TusServletRequest(new org.springframework.mock.web.MockHttpServletRequest()),
        new TusServletResponse(response),
        null,
        "owner");
    assertThat(response.getContentAsString(), containsString("completed-upload"));
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

  @Test
  public void testUploadStorageServiceDefaultMaxAppendSizeGreaterThanZero() {
    UploadStorageService storage =
        new UploadStorageService() {
          // implementations of all required abstract methods...
          @Override
          public me.desair.tus.server.upload.UploadInfo getUploadInfo(String url, String key) {
            return null;
          }

          @Override
          public me.desair.tus.server.upload.UploadInfo getUploadInfo(
              me.desair.tus.server.upload.UploadId id) {
            return null;
          }

          @Override
          public String getUploadUri() {
            return null;
          }

          @Override
          public me.desair.tus.server.upload.UploadInfo append(
              me.desair.tus.server.upload.UploadInfo info, java.io.InputStream stream) {
            return null;
          }

          @Override
          public void setMaxUploadSize(Long size) {}

          @Override
          public long getMaxUploadSize() {
            return 1000L;
          } // Returns > 0

          @Override
          public me.desair.tus.server.upload.UploadInfo create(
              me.desair.tus.server.upload.UploadInfo info, String key) {
            return null;
          }

          @Override
          public void update(me.desair.tus.server.upload.UploadInfo info) {}

          @Override
          public java.io.InputStream getUploadedBytes(String url, String key) {
            return null;
          }

          @Override
          public java.io.InputStream getUploadedBytes(me.desair.tus.server.upload.UploadId id) {
            return null;
          }

          @Override
          public void copyUploadTo(
              me.desair.tus.server.upload.UploadInfo info, java.io.OutputStream stream) {}

          @Override
          public void cleanupExpiredUploads(UploadLockingService lock) {}

          @Override
          public void removeLastNumberOfBytes(
              me.desair.tus.server.upload.UploadInfo info, long bytes) {}

          @Override
          public void terminateUpload(me.desair.tus.server.upload.UploadInfo info) {}

          @Override
          public Long getUploadExpirationPeriod() {
            return null;
          }

          @Override
          public void setUploadExpirationPeriod(Long period) {}

          @Override
          public void setUploadConcatenationService(
              me.desair.tus.server.upload.concatenation.UploadConcatenationService service) {}

          @Override
          public me.desair.tus.server.upload.concatenation.UploadConcatenationService
              getUploadConcatenationService() {
            return null;
          }

          @Override
          public void setIdFactory(me.desair.tus.server.upload.UploadIdFactory factory) {}
        };

    assertThat(storage.getMaxAppendSize(), is(1000L));
  }

  @Test
  public void testDiskStorageServiceMaxAppendSizeReflectionZeroOrNegative() throws Exception {
    DiskStorageService storage = new DiskStorageService("/tmp");
    java.lang.reflect.Field field = DiskStorageService.class.getDeclaredField("maxAppendSize");
    field.setAccessible(true);

    // Set maxAppendSize to 0L directly via reflection to trigger branch: maxAppendSize != null &&
    // maxAppendSize > 0 (where first is true, second is false)
    field.set(storage, 0L);
    storage.setMaxUploadSize(500L);
    // Should fallback to maxUploadSize
    assertThat(storage.getMaxAppendSize(), is(500L));

    // Set maxAppendSize to -10L
    field.set(storage, -10L);
    assertThat(storage.getMaxAppendSize(), is(500L));
  }

  private static class DummyAbstractExtensionWithoutOverride extends AbstractTusExtension {
    @Override
    public String getName() {
      return "dummy-no-override";
    }

    @Override
    public java.util.Collection<HttpMethod> getMinimalSupportedHttpMethods() {
      return java.util.Collections.emptyList();
    }

    @Override
    protected void initValidators(java.util.List<RequestValidator> requestValidators) {}

    @Override
    protected void initRequestHandlers(java.util.List<RequestHandler> requestHandlers) {}
  }

  @Test
  public void testAbstractTusExtensionHandleError8ArgsNullProblemDetails() throws Exception {
    DummyAbstractExtensionWithoutOverride ext = new DummyAbstractExtensionWithoutOverride();
    ext.handleError(HttpMethod.POST, null, null, null, null, null, ProtocolVersion.TUS_1_0_0, null);
  }

  @Test
  public void testRequestHandlerDefaultProcessBranchCoverage() throws Exception {
    RequestHandler mockHandler =
        new RequestHandler() {
          @Override
          public boolean supports(HttpMethod method) {
            return true;
          }

          @Override
          public void process(
              HttpMethod method,
              TusServletRequest servletRequest,
              TusServletResponse servletResponse,
              UploadStorageService uploadStorageService,
              String ownerKey)
              throws IOException, TusException {}

          @Override
          public boolean isErrorHandler() {
            return false;
          }
        };

    // Test branch: uploadLockingService != null, servletRequest == null
    UploadLockingService mockLocking = org.mockito.Mockito.mock(UploadLockingService.class);
    mockHandler.process(HttpMethod.PATCH, null, null, null, mockLocking, "owner", null);

    // Test branch: uploadLockingService == null, servletRequest != null
    TusServletRequest mockRequest = org.mockito.Mockito.mock(TusServletRequest.class);
    mockHandler.process(HttpMethod.PATCH, mockRequest, null, null, null, "owner", null);

    // Test branch: uploadLockingService != null, servletRequest != null
    mockHandler.process(HttpMethod.PATCH, mockRequest, null, null, mockLocking, "owner", null);

    // Test default 5-parameter process delegation to 7-parameter process
    RequestHandler mockHandler7 =
        new RequestHandler() {
          @Override
          public boolean supports(HttpMethod method) {
            return true;
          }

          @Override
          public boolean isErrorHandler() {
            return false;
          }

          @Override
          public HttpProblemDetails process(
              HttpMethod method,
              TusServletRequest servletRequest,
              TusServletResponse servletResponse,
              UploadStorageService uploadStorageService,
              UploadLockingService uploadLockingService,
              String ownerKey,
              TusException exception)
              throws IOException, TusException {
            return null;
          }
        };

    mockHandler7.process(HttpMethod.PATCH, null, null, null, "owner");
  }
}
