package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
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
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * End-to-end integration test verifying upload expiration handling, max-age in {@code Upload-Limit}
 * header, file cleanup via {@code cleanupExpiredUploads()}, and error responses after expiration
 * for the RUFH protocol.
 */
public class ExpirationProtocolRufhTest {

  private static final String UPLOAD_URI = "/test/rufh/upload";
  private static final String OWNER_KEY = "RUFH_USER";
  private static final long EXPIRATION_PERIOD_MS = 1000L; // 1 second
  private static Path storagePath;

  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  private TusFileUploadService tusFileUploadService;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus-rufh-expiration", "data").toAbsolutePath();
    Files.createDirectories(storagePath);
  }

  @AfterClass
  public static void destroyDataFolder() throws IOException {
    FileUtils.deleteDirectory(storagePath.toFile());
  }

  @Before
  public void setUp() throws IOException {
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    tusFileUploadService =
        new TusFileUploadService()
            .withUploadUri(UPLOAD_URI)
            .withStoragePath(storagePath.toAbsolutePath().toString())
            .withSupportedProtocolVersions(ProtocolVersion.RUFH)
            .withUploadExpirationPeriod(EXPIRATION_PERIOD_MS)
            .withDownloadFeature();
  }

  @After
  public void tearDown() throws IOException {
    FileUtils.cleanDirectory(storagePath.toFile());
  }

  @Test
  public void testRufhExpirationLifecycleAndCleanup() throws Exception {
    // 1. Create upload session via RUFH POST
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(201));
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);
    assertThat(uploadLocation, notNullValue());

    // Verify Upload-Limit header contains max-age
    String uploadLimitHeader = servletResponse.getHeader(HttpHeader.UPLOAD_LIMIT);
    assertThat(uploadLimitHeader, notNullValue());
    assertThat(uploadLimitHeader.contains("max-age="), is(true));

    // Verify UploadInfo has expirationTimestamp set
    UploadInfo info = tusFileUploadService.getUploadInfo(uploadLocation, OWNER_KEY);
    assertThat(info, notNullValue());
    assertThat(info.getExpirationTimestamp(), notNullValue());

    // 2. Append chunk via RUFH PATCH
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent("hello".getBytes(StandardCharsets.UTF_8));
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(204));
    uploadLimitHeader = servletResponse.getHeader(HttpHeader.UPLOAD_LIMIT);
    assertThat(uploadLimitHeader, notNullValue());
    assertThat(uploadLimitHeader.contains("max-age="), is(true));

    // 3. Wait for upload expiration period to elapse
    Thread.sleep(EXPIRATION_PERIOD_MS + 200L);

    // 4. Verify subsequent PATCH append returns 404 Not Found for expired upload
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "5");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent("world".getBytes(StandardCharsets.UTF_8));
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_NOT_FOUND));

    // 5. Verify subsequent HEAD offset retrieval returns 404 Not Found for expired upload
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(uploadLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_NOT_FOUND));

    // 6. Verify subsequent GET download request returns 404 Not Found for expired upload
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("GET");
    servletRequest.setRequestURI(uploadLocation);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_NOT_FOUND));

    // 7. Last Step: Trigger cleanup of expired uploads and verify physical files/info are removed
    tusFileUploadService.cleanup();
    UploadInfo expiredInfo = tusFileUploadService.getUploadInfo(uploadLocation, OWNER_KEY);
    assertThat(expiredInfo, is((UploadInfo) null));
  }
}
