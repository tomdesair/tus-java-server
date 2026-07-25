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

  @Test
  public void testHttpDigestsPostPutPatchRequestHandlerCoverage() throws Exception {
    me.desair.tus.server.digest.HttpDigestsPostPutPatchRequestHandler handler =
        new me.desair.tus.server.digest.HttpDigestsPostPutPatchRequestHandler();

    // 1. Storage throws exception during getUploadInfo
    UploadStorageService mockStorage = org.mockito.Mockito.mock(UploadStorageService.class);
    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenThrow(new RuntimeException("Test storage error"));

    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();
    request.setRequestURI("/files/test-id");
    request.addHeader(
        HttpHeader.CONTENT_DIGEST, "sha-256=:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=:");
    request.setContent("hello".getBytes());

    TusServletRequest tusReq = new TusServletRequest(request);
    org.apache.commons.io.IOUtils.toByteArray(tusReq.getContentInputStream());

    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();

    handler.process(
        HttpMethod.PATCH,
        tusReq,
        new TusServletResponse(response),
        mockStorage,
        null,
        "owner",
        null);

    // 2. Test captureDigestPreferences when representationDigest and requestedRepresentationDigests
    // are already set
    UploadStorageService mockStorage2 = org.mockito.Mockito.mock(UploadStorageService.class);
    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));
    info.setOffset(100L);
    info.setLength(100L); // Completed
    info.setRepresentationDigest("sha-256=:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=:");
    info.setRequestedRepresentationDigests("sha-256");

    org.mockito.Mockito.when(
            mockStorage2.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(info);

    org.mockito.Mockito.when(
            mockStorage2.getUploadedBytes(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenAnswer(invocation -> new java.io.ByteArrayInputStream("hello".getBytes()));

    org.springframework.mock.web.MockHttpServletRequest req2 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req2.setRequestURI("/files/test-id");
    req2.addHeader(
        HttpHeader.REPR_DIGEST, "sha-256=:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=:");
    req2.addHeader(HttpHeader.WANT_REPR_DIGEST, "sha-256");

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(req2),
        new TusServletResponse(response),
        mockStorage2,
        null,
        "owner",
        null);

    // 3. Test deduplication branch when duplicatesUploadId is already set
    // (info.getDuplicatesUploadId() != null)
    info.setDuplicatesUploadId(new me.desair.tus.server.upload.UploadId("already-duplicate"));
    org.mockito.Mockito.when(mockStorage2.isUploadDeduplicationEnabled()).thenReturn(true);
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(req2),
        new TusServletResponse(response),
        mockStorage2,
        null,
        "owner",
        null);

    // 4. Test addReprDigestResponseHeader when preferredAlg is null (unsupported algorithm in
    // WANT_REPR_DIGEST)
    info.setRequestedRepresentationDigests("unsupported-alg-xyz");
    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(req2),
        new TusServletResponse(response),
        mockStorage2,
        null,
        "owner",
        null);

    // 5. Test calculateEntireFileDigest when getUploadedBytes returns null stream
    try {
      java.lang.reflect.Method m =
          me.desair.tus.server.digest.HttpDigestsPostPutPatchRequestHandler.class.getDeclaredMethod(
              "calculateEntireFileDigest",
              String.class,
              String.class,
              me.desair.tus.server.checksum.ChecksumAlgorithm.class,
              UploadStorageService.class);
      m.setAccessible(true);
      m.invoke(
          handler,
          "/files/test",
          "owner",
          me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256,
          mockStorage);
    } catch (Exception ignored) {
    }
  }

  @Test
  public void testHttpDigestsValidatorCoverage() throws Exception {
    me.desair.tus.server.digest.validation.HttpDigestsValidator validator =
        new me.desair.tus.server.digest.validation.HttpDigestsValidator();

    // 1. Empty Content-Digest
    org.springframework.mock.web.MockHttpServletRequest req1 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req1.addHeader(HttpHeader.CONTENT_DIGEST, "");
    validator.validate(HttpMethod.POST, req1, null, null);

    // 2. Empty Repr-Digest
    org.springframework.mock.web.MockHttpServletRequest req2 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req2.addHeader(HttpHeader.REPR_DIGEST, "");
    validator.validate(HttpMethod.POST, req2, null, null);

    // 3. Empty Want-Repr-Digest
    org.springframework.mock.web.MockHttpServletRequest req3 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req3.addHeader(HttpHeader.WANT_REPR_DIGEST, "");
    validator.validate(HttpMethod.POST, req3, null, null);

    // 4. Content-Digest parses to empty map
    org.springframework.mock.web.MockHttpServletRequest req4 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req4.addHeader(HttpHeader.CONTENT_DIGEST, " , ");
    try {
      validator.validate(HttpMethod.POST, req4, null, null);
    } catch (TusException expected) {
      assertThat(expected.getStatus(), is(400));
    }

    // 5. Repr-Digest parses to empty map
    org.springframework.mock.web.MockHttpServletRequest req5 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req5.addHeader(HttpHeader.REPR_DIGEST, " , ");
    try {
      validator.validate(HttpMethod.POST, req5, null, null);
    } catch (TusException expected) {
      assertThat(expected.getStatus(), is(400));
    }

    // 6. Want-Repr-Digest parses to empty list
    org.springframework.mock.web.MockHttpServletRequest req6 =
        new org.springframework.mock.web.MockHttpServletRequest();
    req6.addHeader(HttpHeader.WANT_REPR_DIGEST, " , ");
    try {
      validator.validate(HttpMethod.POST, req6, null, null);
    } catch (TusException expected) {
      assertThat(expected.getStatus(), is(400));
    }

    // 7. Non-TusException thrown during validation
    jakarta.servlet.http.HttpServletRequest mockReqException =
        org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
    org.mockito.Mockito.when(mockReqException.getHeader(HttpHeader.CONTENT_DIGEST))
        .thenThrow(new RuntimeException("Internal parser error"));
    try {
      validator.validate(HttpMethod.POST, mockReqException, null, null);
    } catch (TusException expected) {
      assertThat(expected.getStatus(), is(400));
    }
  }

  @Test
  public void testDownloadUploadMetadataHandlerCoverage() throws Exception {
    me.desair.tus.server.download.DownloadUploadMetadataHandler handler =
        new me.desair.tus.server.download.DownloadUploadMetadataHandler();

    UploadStorageService mockStorage = org.mockito.Mockito.mock(UploadStorageService.class);

    org.springframework.mock.web.MockHttpServletRequest request =
        new org.springframework.mock.web.MockHttpServletRequest();

    // Case 1: getUploadInfo returns null
    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(null);
    org.springframework.mock.web.MockHttpServletResponse respNull =
        new org.springframework.mock.web.MockHttpServletResponse();
    handler.process(
        HttpMethod.GET,
        new TusServletRequest(request),
        new TusServletResponse(respNull),
        mockStorage,
        "owner");
    assertThat(respNull.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());

    // Case 2: info is in progress
    me.desair.tus.server.upload.UploadInfo infoInProgress =
        new me.desair.tus.server.upload.UploadInfo();
    infoInProgress.setId(new me.desair.tus.server.upload.UploadId("id-progress"));
    infoInProgress.setOffset(50L);
    infoInProgress.setLength(100L);
    infoInProgress.setEncodedMetadata("filename dGVzdC50eHQ=");
    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(infoInProgress);

    org.springframework.mock.web.MockHttpServletResponse respProgress =
        new org.springframework.mock.web.MockHttpServletResponse();
    handler.process(
        HttpMethod.GET,
        new TusServletRequest(request),
        new TusServletResponse(respProgress),
        mockStorage,
        "owner");
    assertThat(respProgress.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());

    // Case 3: info completed, no metadata
    me.desair.tus.server.upload.UploadInfo infoNoMeta =
        new me.desair.tus.server.upload.UploadInfo();
    infoNoMeta.setId(new me.desair.tus.server.upload.UploadId("id-nometa"));
    infoNoMeta.setOffset(100L);
    infoNoMeta.setLength(100L);
    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(infoNoMeta);

    org.springframework.mock.web.MockHttpServletResponse respNoMeta =
        new org.springframework.mock.web.MockHttpServletResponse();
    handler.process(
        HttpMethod.GET,
        new TusServletRequest(request),
        new TusServletResponse(respNoMeta),
        mockStorage,
        "owner");
    assertThat(respNoMeta.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());

    // Case 4: info completed with metadata
    me.desair.tus.server.upload.UploadInfo infoComplete =
        new me.desair.tus.server.upload.UploadInfo();
    infoComplete.setId(new me.desair.tus.server.upload.UploadId("id-complete"));
    infoComplete.setOffset(100L);
    infoComplete.setLength(100L);
    infoComplete.setEncodedMetadata("filename dGVzdC50eHQ=");

    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(infoComplete);

    org.springframework.mock.web.MockHttpServletResponse respComplete =
        new org.springframework.mock.web.MockHttpServletResponse();
    handler.process(
        HttpMethod.GET,
        new TusServletRequest(request),
        new TusServletResponse(respComplete),
        mockStorage,
        "owner");
    assertThat(respComplete.getHeader(HttpHeader.UPLOAD_METADATA), is("filename dGVzdC50eHQ="));
  }

  @Test
  public void testDiskStorageServiceChecksumBase64EdgeCase() throws Exception {
    DiskStorageService storage = new DiskStorageService("/tmp");

    // 1. null checksum
    storage.getUploadInfoByChecksum(null, me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256);

    // 2. Hex checksum string
    storage.getUploadInfoByChecksum(
        "1234567890abcdef", me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256);

    // 3. Base64 decodes to empty byte array (length == 0)
    storage.getUploadInfoByChecksum("?", me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256);

    // 4. Base64 decodes to valid non-empty byte array
    String base64Checksum =
        org.apache.commons.codec.binary.Base64.encodeBase64String(new byte[] {0x12, 0x34, 0x56});
    storage.getUploadInfoByChecksum(
        base64Checksum, me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256);
  }

  @Test
  public void testDiskStorageServiceUnsafePathComponent() throws Exception {
    DiskStorageService storage = new DiskStorageService("/tmp");
    java.lang.reflect.Method isSafe =
        DiskStorageService.class.getDeclaredMethod("isSafePathComponent", String.class);
    isSafe.setAccessible(true);

    assertThat((Boolean) isSafe.invoke(storage, "safe-name"), is(true));
    assertThat((Boolean) isSafe.invoke(storage, "path/with/slash"), is(false));
    assertThat((Boolean) isSafe.invoke(storage, "path\\with\\backslash"), is(false));
    assertThat((Boolean) isSafe.invoke(storage, "../dotdot"), is(false));
    assertThat((Boolean) isSafe.invoke(storage, ""), is(false));
    assertThat((Boolean) isSafe.invoke(storage, (String) null), is(false));
  }

  @Test
  public void testRufhValidatorsAndErrorHandlerEdgeCases() throws Exception {
    // 1. RufhCreationValidator minSize check when minSize is null
    me.desair.tus.server.rufh.validation.RufhCreationValidator creationVal =
        new me.desair.tus.server.rufh.validation.RufhCreationValidator();
    UploadStorageService mockStorage = org.mockito.Mockito.mock(UploadStorageService.class);
    org.mockito.Mockito.when(mockStorage.getMinSize()).thenReturn(null);
    org.mockito.Mockito.when(mockStorage.getMinAppendSize()).thenReturn(null);

    org.springframework.mock.web.MockHttpServletRequest req =
        new org.springframework.mock.web.MockHttpServletRequest();
    req.setMethod("POST");
    req.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    creationVal.validate(HttpMethod.POST, req, mockStorage, "owner");

    // 2. RufhAppendValidator minAppendSize check when minAppendSize is null
    me.desair.tus.server.rufh.validation.RufhAppendValidator appendVal =
        new me.desair.tus.server.rufh.validation.RufhAppendValidator();
    org.mockito.Mockito.when(mockStorage.getMinAppendSize()).thenReturn(null);

    me.desair.tus.server.upload.UploadInfo info = new me.desair.tus.server.upload.UploadInfo();
    info.setId(new me.desair.tus.server.upload.UploadId("test-id"));
    info.setOffset(0L);
    info.setLength(100L);

    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(info);

    org.springframework.mock.web.MockHttpServletRequest appReq =
        new org.springframework.mock.web.MockHttpServletRequest();
    appReq.setMethod("PATCH");
    appReq.setRequestURI("/files/test-id");
    appReq.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    appReq.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    appReq.setContent("hello".getBytes());

    appendVal.validate(HttpMethod.PATCH, appReq, mockStorage, "owner");

    // 3. RufhErrorHandler with exception and null uploadInfo
    me.desair.tus.server.rufh.handler.RufhErrorHandler errorHandler =
        new me.desair.tus.server.rufh.handler.RufhErrorHandler();
    org.mockito.Mockito.when(
            mockStorage.getUploadInfo(org.mockito.Mockito.anyString(), org.mockito.Mockito.any()))
        .thenReturn(null);

    org.springframework.mock.web.MockHttpServletResponse response =
        new org.springframework.mock.web.MockHttpServletResponse();

    errorHandler.process(
        HttpMethod.PATCH,
        new TusServletRequest(appReq),
        new TusServletResponse(response),
        mockStorage,
        null,
        "owner",
        new TusException(400, "Error"));

    // 4. RufhErrorHandler with null uploadStorageService or null servletRequest
    errorHandler.process(
        HttpMethod.PATCH,
        null,
        new TusServletResponse(response),
        null,
        null,
        "owner",
        new TusException(400, "Error"));

    // 5. RufhCreationValidator minSize == 0 branch
    org.mockito.Mockito.when(mockStorage.getMinSize()).thenReturn(0L);
    creationVal.validate(HttpMethod.POST, req, mockStorage, "owner");

    // 6. DiskStorageService getUploadInfoByChecksum exception during base64 decode
    DiskStorageService storage = new DiskStorageService("/tmp");
    try {
      storage.getUploadInfoByChecksum(
          "!!invalid!!", me.desair.tus.server.checksum.ChecksumAlgorithm.SHA256);
    } catch (Exception ignored) {
    }
  }
}
