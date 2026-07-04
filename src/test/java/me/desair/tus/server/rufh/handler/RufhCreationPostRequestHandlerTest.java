package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhCreationPostRequestHandlerTest {

  private RufhCreationPostRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;
  @Mock private UploadLockingService lockingService;

  @Before
  public void setUp() {
    handler = new RufhCreationPostRequestHandler(null);
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();

    when(storageService.getUploadUri()).thenReturn("/files");
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.POST));
    assertTrue(handler.supports(HttpMethod.PUT));
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertFalse(handler.supports(HttpMethod.GET));
  }

  /**
   * Section 4.2.2 (Upload Creation - Server Behavior): "If the Upload-Complete header field is set
   * to false... the server MUST include the Location response header field pointing to the upload
   * resource... Servers are RECOMMENDED to use the 201 (Created) status code."
   */
  @Test
  public void testProcessPartialUploadCreation() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("0"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));
  }

  /**
   * Section 4.2.2 (Upload Creation - Streaming & Lock Registration): Tests that the creation
   * request input stream is wrapped and registered for lock contention resolution.
   */
  @Test
  public void testProcessCreationRegistersInputStream() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("creation body".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    verify(lockingService)
        .registerInputStream(eq("/files/creation-id"), any(InterruptibleInputStream.class));
  }

  @Test
  public void testProcessWithInterimResponseStrategyAndNullBaseUri() throws Exception {
    final java.util.List<String> interimUris = new java.util.ArrayList<>();
    me.desair.tus.server.rufh.InterimResponseStrategy strategy =
        (res, uri, offset) -> interimUris.add(uri);

    handler = new RufhCreationPostRequestHandler(strategy);

    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);

    // Mock getUploadUri to return null to test fallback to requestURI
    when(storageService.getUploadUri()).thenReturn(null);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
    assertThat(interimUris.size(), is(1));
    assertThat(interimUris.get(0), is("/files/creation-id"));
  }

  @Test
  public void testProcessExistingUploadPatchReturnsEarly() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/existing-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("existing-id"));
    when(storageService.getUploadInfo("/files/existing-id", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    // Early return, default status should remain (200) and no location header set
    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.LOCATION), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessWithoutLockingServiceAndAppendReturnsNull() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.setContent("creation body".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    // storageService.append returns null
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(null);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("0"));
  }

  @Test
  public void testProcessUploadLimitsHeader() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    // Test when max upload size > 0 and max append size > 0
    when(storageService.getMaxUploadSize()).thenReturn(10000L);
    when(storageService.getMaxAppendSize()).thenReturn(5000L);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(
        response.getHeader(HttpHeader.UPLOAD_LIMIT), is("max-size=10000, max-append-size=5000"));

    // Test when max upload size is 0 and max append size is null/0
    response = new MockHttpServletResponse();
    when(storageService.getMaxUploadSize()).thenReturn(0L);
    when(storageService.getMaxAppendSize()).thenReturn(null);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getHeader(HttpHeader.UPLOAD_LIMIT), org.hamcrest.CoreMatchers.nullValue());
  }

  @Test
  public void testProcessPatchCreationWhenUploadDoesNotExist() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/does-not-exist");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(1000L);
    info.setOffset(0L);

    when(storageService.getUploadInfo("/files/does-not-exist", "owner")).thenReturn(null);
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(info);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
  }

  @Test
  public void testProcessNegativeLengthAndNullUploadId() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "-500");

    UploadInfo info = new UploadInfo();
    info.setId(null); // Null ID
    info.setOffset(0L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    // Location header ends with "/" because ID is empty string
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/"));
  }

  @Test
  public void testProcessNullInputStreamOrZeroContentLengthAndFinishedState() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    // Content length is 0
    request.setContent(new byte[0]);

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setOffset(1000L);
    info.setLength(1000L);

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    // Since UPLOAD_COMPLETE is ?1 (true), it should set status to 200
    assertThat(response.getStatus(), is(200));
  }

  @Test
  public void testProcessBaseUriEndsWithSlashAndNullInputStreamWithContentLength()
      throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setLength(5000L);
    info.setOffset(0L);

    // baseUri ends with slash
    when(storageService.getUploadUri()).thenReturn("/files/");
    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    // Create a custom request where input stream is null but content length > 0
    TusServletRequest customRequest =
        new TusServletRequest(request) {
          @Override
          public java.io.InputStream getContentInputStream() {
            return null;
          }

          @Override
          public long getContentLengthLong() {
            return 100L;
          }
        };

    handler.process(
        HttpMethod.POST,
        customRequest,
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(201));
    assertThat(response.getHeader(HttpHeader.LOCATION), is("/files/creation-id"));
  }

  @Test
  public void testProcessFinishedStateWithUploadCompleted() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0"); // false, but offset == length below

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("creation-id"));
    info.setOffset(1000L);
    info.setLength(1000L); // Completed

    when(storageService.create(any(UploadInfo.class), nullable(String.class))).thenReturn(info);

    handler.process(
        HttpMethod.POST,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner",
        null);

    assertThat(response.getStatus(), is(200));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));
  }
}
