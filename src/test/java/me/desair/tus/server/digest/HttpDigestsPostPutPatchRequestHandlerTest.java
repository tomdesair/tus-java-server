package me.desair.tus.server.digest;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UploadDigestMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class HttpDigestsPostPutPatchRequestHandlerTest {

  private HttpDigestsPostPutPatchRequestHandler handler;
  private UploadStorageService uploadStorageService;
  private UploadLockingService uploadLockingService;

  @Before
  public void setUp() {
    handler = new HttpDigestsPostPutPatchRequestHandler();
    uploadStorageService = mock(UploadStorageService.class);
    uploadLockingService = mock(UploadLockingService.class);
  }

  @Test
  public void testSupports() {
    assertThat(handler.supports(HttpMethod.POST), is(true));
    assertThat(handler.supports(HttpMethod.PUT), is(true));
    assertThat(handler.supports(HttpMethod.PATCH), is(true));
    assertThat(handler.supports(HttpMethod.GET), is(false));
  }

  @Test
  public void testProcessNoHeaders() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.setRequestURI("/files/123");
    TusServletRequest servletRequest = new TusServletRequest(request);
    TusServletResponse servletResponse =
        new TusServletResponse(new org.springframework.mock.web.MockHttpServletResponse());

    handler.process(
        HttpMethod.POST,
        servletRequest,
        servletResponse,
        uploadStorageService,
        uploadLockingService,
        "owner",
        null);

    verify(uploadStorageService, never()).update(any());
  }

  @Test
  public void testProcessContentDigestMismatch() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("POST");
    request.addHeader(HttpHeader.CONTENT_DIGEST, "sha-256=:invalid-digest-here=:");
    request.setContent("hello".getBytes(StandardCharsets.UTF_8));

    TusServletRequest servletRequest = new TusServletRequest(request);
    // Trigger body reading to compute checksum
    byte[] buffer = new byte[100];
    servletRequest.getContentInputStream().read(buffer);

    TusServletResponse servletResponse =
        new TusServletResponse(new org.springframework.mock.web.MockHttpServletResponse());

    assertThrows(
        UploadDigestMismatchException.class,
        () ->
            handler.process(
                HttpMethod.POST,
                servletRequest,
                servletResponse,
                uploadStorageService,
                uploadLockingService,
                "owner",
                null));
  }

  @Test
  public void testProcessContentDigestMatch() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("PATCH");
    request.setRequestURI("/files/123");
    request.addHeader(
        HttpHeader.CONTENT_DIGEST,
        "sha-256=:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=:, md5=:XUFAKrxLKna5cZ2REBfFkg==:");
    request.setContent("hello".getBytes(StandardCharsets.UTF_8));

    TusServletRequest servletRequest = new TusServletRequest(request);
    // Trigger body reading to compute checksum
    byte[] buffer = new byte[100];
    int bytesRead = servletRequest.getContentInputStream().read(buffer);

    TusServletResponse servletResponse =
        new TusServletResponse(new org.springframework.mock.web.MockHttpServletResponse());

    UploadInfo info = new UploadInfo();
    info.setLength(200L);
    info.setOffset(100L);
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        servletRequest,
        servletResponse,
        uploadStorageService,
        uploadLockingService,
        "owner",
        null);

    // No exception thrown, and no upload representation digests updated yet
    verify(uploadStorageService, never()).update(any());
  }

  @Test
  public void testProcessWithReprDigestRequestedAndUploadComplete() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("PATCH");
    request.setRequestURI("/files/123");
    request.addHeader(HttpHeader.WANT_REPR_DIGEST, "sha-256");

    TusServletRequest servletRequest = new TusServletRequest(request);
    TusServletResponse servletResponse =
        new TusServletResponse(new org.springframework.mock.web.MockHttpServletResponse());

    UploadInfo info = new UploadInfo();
    info.setLength(100L);
    info.setOffset(100L);
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);
    when(uploadStorageService.getUploadedBytes("/files/123", "owner"))
        .thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

    handler.process(
        HttpMethod.PATCH,
        servletRequest,
        servletResponse,
        uploadStorageService,
        uploadLockingService,
        "owner",
        null);

    verify(uploadStorageService).update(info);
    assertThat(info.getRequestedRepresentationDigests(), is("sha-256"));
    assertThat(
        servletResponse.getHeader(HttpHeader.REPR_DIGEST),
        is("sha-256=:LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=:"));
  }
}
