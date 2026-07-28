package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
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
public class RufhDeleteRequestHandlerTest {

  private RufhDeleteRequestHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    handler = new RufhDeleteRequestHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.DELETE));
    assertFalse(handler.supports(HttpMethod.POST));
  }

  /**
   * Section 7 (Upload Cancellation): "If the client determines that it no longer needs the upload
   * resource, it CAN request its deletion by sending a DELETE request to the upload resource URL...
   * The server MUST acknowledge a successful upload cancellation with a 2xx status code."
   */
  @Test
  public void testProcessDeleteRequest() throws Exception {
    request.setRequestURI("/files/delete-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("delete-id"));
    when(storageService.getUploadInfo("/files/delete-id", "owner")).thenReturn(info);

    handler.process(
        HttpMethod.DELETE,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
    verify(storageService).terminateUpload(info);
  }

  @Test
  public void testProcessWithNullUploadInfo() throws Exception {
    request.setRequestURI("/files/delete-id");
    when(storageService.getUploadInfo("/files/delete-id", "owner")).thenReturn(null);

    handler.process(
        HttpMethod.DELETE,
        new TusServletRequest(request),
        new TusServletResponse(response),
        storageService,
        null,
        "owner",
        null);

    assertThat(response.getStatus(), is(204));
  }
}
