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
        "owner");

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
        "owner");

    verify(lockingService)
        .registerInputStream(eq("/files/creation-id"), any(InterruptibleInputStream.class));
  }
}
