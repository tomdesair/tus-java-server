package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.TusFileUploadService;
import org.apache.commons.io.FileUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** End-to-end integration tests for the download extension with RUFH protocol. */
public class DownloadProtocolRufhTest {

  private static final String UPLOAD_URI = "/test/upload";
  private static final String OWNER_KEY = "JOHN_DOE";
  private static Path storagePath;

  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  private TusFileUploadService tusFileUploadService;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus-rufh-download", "data").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    tusFileUploadService =
        new TusFileUploadService()
            .withUploadUri(UPLOAD_URI)
            .withStoragePath(storagePath.toAbsolutePath().toString())
            .withSupportedProtocolVersions(ProtocolVersion.RUFH)
            .withDownloadFeature();
  }

  @Test
  public void testDownloadCompletedRufhUpload() throws Exception {
    // 1. Create upload session
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "14");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename dGVzdC5qcGc="); // test.jpg
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(201));
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Append chunk and complete upload
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent("hello download".getBytes(StandardCharsets.UTF_8));
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(200));

    // 3. Download the upload via GET
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(uploadLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_OK));
    assertThat(servletResponse.getContentAsString(), is("hello download"));
    assertThat(servletResponse.getHeader(HttpHeader.CONTENT_LENGTH), is("14"));
    String uploadId = uploadLocation.substring(uploadLocation.lastIndexOf('/') + 1);
    assertThat(
        servletResponse.getHeader(HttpHeader.CONTENT_DISPOSITION),
        is(String.format("attachment; filename=\"%s\"; filename*=UTF-8''%s", uploadId, uploadId)));

    // 4. Assert that all Tus-specific response headers are completely absent
    assertThat(servletResponse.getHeader(HttpHeader.TUS_RESUMABLE), nullValue());
    assertThat(servletResponse.getHeader(HttpHeader.TUS_VERSION), nullValue());
    assertThat(servletResponse.getHeader(HttpHeader.TUS_EXTENSION), nullValue());
    assertThat(servletResponse.getHeader(HttpHeader.TUS_MAX_SIZE), nullValue());
    assertThat(servletResponse.getHeader(HttpHeader.TUS_CHECKSUM_ALGORITHM), nullValue());
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_METADATA), nullValue());
  }

  @Test
  public void testDownloadInProgressRufhUpload() throws Exception {
    // 1. Create upload session
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "14");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(201));
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Download in-progress upload via GET
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(uploadLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Should return 422 Unprocessable Entity
    assertThat(servletResponse.getStatus(), is(422));
  }

  @Test
  public void testDownloadFeatureDisabled() throws Exception {
    // 1. Setup service without download feature
    TusFileUploadService serviceWithoutDownload =
        new TusFileUploadService()
            .withUploadUri(UPLOAD_URI)
            .withStoragePath(storagePath.toAbsolutePath().toString())
            .withSupportedProtocolVersions(ProtocolVersion.RUFH);

    // 2. Create upload session
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "14");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent("hello download".getBytes(StandardCharsets.UTF_8));
    serviceWithoutDownload.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(200));
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 3. Download via GET
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(uploadLocation);
    serviceWithoutDownload.process(servletRequest, servletResponse, OWNER_KEY);

    // Without the download feature, GET is not processed and defaults to 200 OK
    assertThat(servletResponse.getStatus(), is(200));
    assertThat(servletResponse.getContentAsString(), is(""));
  }
}
