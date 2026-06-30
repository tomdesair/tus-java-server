package me.desair.tus.server.download;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DownloadGetRequestHandlerCRLFTest {

  private DownloadGetRequestHandler handler;

  @Mock private TusServletRequest servletRequest;
  @Mock private TusServletResponse servletResponse;
  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    handler = new DownloadGetRequestHandler();
  }

  @Test
  public void testCRLFInjectionInMimeType() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("test-id"));
    info.setLength(10L);
    info.setOffset(10L);
    // Base64 of "text/html\r\n\r\n<script>alert(1)</script>"
    String maliciousMimeTypeBase64 =
        Base64.getEncoder().encodeToString("text/html\r\n\r\n<script>alert(1)</script>".getBytes());
    info.setEncodedMetadata("filetype " + maliciousMimeTypeBase64);

    when(servletRequest.getRequestURI()).thenReturn("/upload/test");
    when(uploadStorageService.getUploadInfo("/upload/test", "owner")).thenReturn(info);

    handler.process(HttpMethod.GET, servletRequest, servletResponse, uploadStorageService, "owner");

    ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);
    verify(servletResponse).setHeader(eq(HttpHeader.CONTENT_TYPE), contentTypeCaptor.capture());

    assertThat(contentTypeCaptor.getValue(), is("text/html<script>alert(1)</script>"));
  }
}
