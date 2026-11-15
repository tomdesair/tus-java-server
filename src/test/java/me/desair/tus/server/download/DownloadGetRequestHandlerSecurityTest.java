package me.desair.tus.server.download;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class DownloadGetRequestHandlerSecurityTest {

  private DownloadGetRequestHandler handler;
  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  private TusServletRequest tusRequest;
  private TusServletResponse tusResponse;
  private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    handler = new DownloadGetRequestHandler();
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    tusRequest = new TusServletRequest(servletRequest);
    tusResponse = new TusServletResponse(servletResponse);
    uploadStorageService = mock(UploadStorageService.class);
  }

  @Test
  public void testCrlfInjectionInFileName() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("123"));
    info.setLength(10L);
    info.setOffset(10L); // finished
    // filename: "test\r\nInjected: true.txt"
    String maliciousFileName = "test\r\nInjected: true.txt\"";
    String encodedMeta =
        "filename " + Base64.getEncoder().encodeToString(maliciousFileName.getBytes());
    info.setEncodedMetadata(encodedMeta);

    when(uploadStorageService.getUploadInfo(anyString(), any())).thenReturn(info);

    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);

    String contentDisposition = servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION);

    // The filename parameter should not contain \r, \n, or "
    assertThat(
        contentDisposition,
        is(
            "attachment; filename=\"testInjected: true.txt\";"
                + " filename*=UTF-8''test%0D%0AInjected%3A%20true.txt%22"));
  }

  @Test
  public void testPathTraversalInFileName() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("upload-id-123"));
    info.setLength(10L);
    info.setOffset(10L);

    // 1. Unix directory traversal path
    info.setEncodedMetadata(
        "filename " + Base64.getEncoder().encodeToString("../../../../etc/passwd".getBytes()));
    when(uploadStorageService.getUploadInfo(anyString(), any())).thenReturn(info);
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"passwd\"; filename*=UTF-8''passwd"));

    // 2. Windows directory traversal path
    servletResponse = new MockHttpServletResponse();
    tusResponse = new TusServletResponse(servletResponse);
    info.setEncodedMetadata(
        "filename " + Base64.getEncoder().encodeToString("..\\..\\malicious.bat".getBytes()));
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"malicious.bat\"; filename*=UTF-8''malicious.bat"));

    // 3. Traversal sequence only falls back to upload ID
    servletResponse = new MockHttpServletResponse();
    tusResponse = new TusServletResponse(servletResponse);
    info.setEncodedMetadata("filename " + Base64.getEncoder().encodeToString("../..".getBytes()));
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"upload-id-123\";" + " filename*=UTF-8''upload-id-123"));

    // 4. Blank filename falls back to upload ID
    servletResponse = new MockHttpServletResponse();
    tusResponse = new TusServletResponse(servletResponse);
    info.setEncodedMetadata("filename " + Base64.getEncoder().encodeToString("   ".getBytes()));
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"upload-id-123\";" + " filename*=UTF-8''upload-id-123"));

    // 5. Null or blank filename falls back to upload ID or default "download"
    servletResponse = new MockHttpServletResponse();
    tusResponse = new TusServletResponse(servletResponse);
    info.setId(new UploadId("fallback-id"));
    info.setEncodedMetadata("filename");
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"fallback-id\"; filename*=UTF-8''fallback-id"));

    servletResponse = new MockHttpServletResponse();
    tusResponse = new TusServletResponse(servletResponse);
    info.setId(null);
    info.setEncodedMetadata("filename");
    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is("attachment; filename=\"download\"; filename*=UTF-8''download"));
  }

  @Test
  public void testXContentTypeOptionsHeader() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("123"));
    info.setLength(10L);
    info.setOffset(10L);
    when(uploadStorageService.getUploadInfo(anyString(), any())).thenReturn(info);

    handler.process(HttpMethod.GET, tusRequest, tusResponse, uploadStorageService, null);

    assertThat(servletResponse.getHeader(HttpHeader.X_CONTENT_TYPE_OPTIONS), is("nosniff"));
  }
}
