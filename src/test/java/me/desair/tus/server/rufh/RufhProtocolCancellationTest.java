package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
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
public class RufhProtocolCancellationTest {

  private ResumableUploadsForHttpProtocol protocol;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    protocol = new ResumableUploadsForHttpProtocol();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();

    when(storageService.getUploadUri()).thenReturn("/files");
  }

  /**
   * Section 7 (Upload Cancellation): "If the client determines that it no longer needs the upload
   * resource, it CAN request its deletion by sending a DELETE request to the upload resource URL...
   * The server MUST acknowledge a successful upload cancellation with a 2xx status code."
   */
  @Test
  public void testUploadCancellationSuccess() throws Exception {
    request.setMethod("DELETE");
    request.setRequestURI("/files/test-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setOffset(500L);

    when(storageService.getUploadInfo("/files/test-id", null)).thenReturn(info);

    protocol.validate(HttpMethod.DELETE, request, storageService, null, null, ProtocolVersion.RUFH);
    protocol.process(
        HttpMethod.DELETE,
        new TusServletRequest(request, true),
        new TusServletResponse(response),
        storageService,
        null,
        null,
        ProtocolVersion.RUFH);

    assertThat(response.getStatus(), is(204));
    verify(storageService).terminateUpload(info);
  }
}
