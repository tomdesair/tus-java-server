package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
public class RufhAppendPatchRequestHandlerTest {

  private RufhAppendPatchRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;
  @Mock private UploadLockingService lockingService;

  @Before
  public void setUp() {
    handler = new RufhAppendPatchRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertFalse(handler.supports(HttpMethod.POST));
  }

  /**
   * Section 5.2 (Upload Append - Server Behavior): "If the Upload-Complete request header field is
   * set to false... the upload resource acknowledges the appended data by sending a 2xx response
   * with the Upload-Complete header field set to false."
   */
  @Test
  public void testProcessPartialAppend() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/append-id");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    request.setContent("append data".getBytes());

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("append-id"));
    info.setOffset(1000L);
    info.setLength(5000L);

    UploadInfo updated = new UploadInfo();
    updated.setId(info.getId());
    updated.setOffset(1011L);
    updated.setLength(5000L);

    when(storageService.getUploadInfo("/files/append-id", "owner")).thenReturn(info);
    when(storageService.append(any(UploadInfo.class), any())).thenReturn(updated);

    handler.process(
        HttpMethod.PATCH,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        lockingService,
        "owner");

    assertThat(response.getStatus(), is(204));
    assertThat(response.getHeader(HttpHeader.UPLOAD_OFFSET), is("1011"));
    assertThat(response.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?0"));

    verify(lockingService)
        .registerInputStream(eq("/files/append-id"), any(InterruptibleInputStream.class));
  }
}
