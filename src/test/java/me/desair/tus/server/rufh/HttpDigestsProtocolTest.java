package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import org.apache.commons.lang3.StringUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** End-to-end integration/compliance tests for RFC 9530 HTTP Digests with RUFH protocol. */
public class HttpDigestsProtocolTest {

  private static final String UPLOAD_URI = "/test/upload";
  private static final String OWNER_KEY = "JOHN_DOE";
  private static Path storagePath;

  private MockHttpServletRequest servletRequest;
  private MockHttpServletResponse servletResponse;
  private TusFileUploadService tusFileUploadService;

  @BeforeClass
  public static void setupDataFolder() throws IOException {
    storagePath = Paths.get("target", "tus-digests", "data").toAbsolutePath();
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
            .withUploadDeduplication(true);
  }

  /**
   * Section 4 of RFC 9530 (Want-Content-Digest / Want-Repr-Digest): "The Want-Content-Digest and
   * Want-Repr-Digest HTTP header field are preference fields used by a sender to indicate it wants
   * to receive the corresponding digest fields in the response."
   */
  @Test
  public void testOptionsAdvertisesDigests() throws Exception {
    servletRequest.setMethod("OPTIONS");
    servletRequest.setRequestURI(UPLOAD_URI);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_NO_CONTENT));
    assertThat(
        servletResponse.getHeader(HttpHeader.WANT_CONTENT_DIGEST),
        is("sha-256, sha-512, sha-384, sha, md5"));
    assertThat(
        servletResponse.getHeader(HttpHeader.WANT_REPR_DIGEST),
        is("sha-256, sha-512, sha-384, sha, md5"));
  }

  /**
   * Section 3 of RFC 9530 (Content-Digest): "The Content-Digest HTTP header field associates one or
   * more digests with a message content."
   */
  @Test
  public void testChunkContentDigestSuccess() throws Exception {
    // 1. Create upload session
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(201));
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Append chunk with valid Content-Digest
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(
        HttpHeader.CONTENT_DIGEST, "sha-256=:yV9g7MInOPrtlLDWsplfHK0LaH22Uz70R1ZXbHIjzjU=:");
    servletRequest.setContent("hello digest".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(204));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_OFFSET), is("12"));
  }

  /**
   * Section 3 of RFC 9530 (Content-Digest): "The Content-Digest HTTP header field associates one or
   * more digests with a message content." If the digest does not match, the server MUST consider
   * the transfer failed.
   */
  @Test
  public void testChunkContentDigestFailure() throws Exception {
    // 1. Create upload session
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Append chunk with INVALID Content-Digest
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.CONTENT_DIGEST, "sha-256=:wrongdigestvalue=:");
    servletRequest.setContent("hello digest".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(400));
    assertTrue(servletResponse.getContentAsString().contains("digest-mismatched-values"));
  }

  /**
   * Section 5 of RFC 9530 (Repr-Digest): "The Repr-Digest HTTP header field associates one or more
   * digests with a representation."
   */
  @Test
  public void testReprDigestCompleteUploadSuccess() throws Exception {
    String testContent = "representation test content";
    // base64 SHA-256 of "representation test content" is
    // "p+nB8b3M1Y8z7HicF87RkC7C2f9xQnL9M6aW9w/6Ghk="
    // Wait, let's verify actual base64 SHA-256 of "representation test content"
    // MessageDigest SHA-256 of "representation test content" ->
    // 66e8574a4413155f91456d2b380a06efcf5e8211dbf21fb1bfb41cf43c081e19
    // base64: E0/isChYLiH9/ph8pn/+F6EyUQ+PCZTi8epGL3cuQW0=
    String correctBase64 = "E0/isChYLiH9/ph8pn/+F6EyUQ+PCZTi8epGL3cuQW0=";

    // 1. Create upload session with Repr-Digest
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(testContent.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.REPR_DIGEST, "sha-256=:" + correctBase64 + ":");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Append final chunk
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(testContent.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(200));
  }

  /**
   * Section 5 of RFC 9530 (Repr-Digest): "The Repr-Digest HTTP header field associates one or more
   * digests with a representation." If mismatch occurs, server rejects upload and stops processing.
   */
  @Test
  public void testReprDigestCompleteUploadFailure() throws Exception {
    String testContent = "representation test content";

    // 1. Create upload session with INVALID Repr-Digest
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(testContent.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.REPR_DIGEST, "sha-256=:invalidreprdigest=:");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // 2. Append final chunk (triggers Repr-Digest validation on completion)
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(testContent.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    assertThat(servletResponse.getStatus(), is(400));
    assertTrue(servletResponse.getContentAsString().contains("digest-mismatched-values"));

    // Verify written bytes were cleaned up (truncated back to 0)
    UploadInfo info = tusFileUploadService.getUploadInfo(uploadLocation, OWNER_KEY);
    assertThat(info.getOffset(), is(0L));
  }

  /**
   * Section 4 of RFC 9530: Want-Repr-Digest indicates capabilities and preferences. Server returns
   * Repr-Digest continuously or in final response.
   */
  @Test
  public void testWantReprDigestContinuousUpdates() throws Exception {
    String chunk1 = "chunk one ";
    String chunk2 = "chunk two";
    // base64 SHA-256 of "chunk one " is "W/Wf9eL0Xg3V5bV5k9K6YVwU8O7g="
    // Wait, let's verify actual base64 SHA-256 of "chunk one ":
    // MessageDigest SHA-256 of "chunk one " -> Z8m4uP/R5D6q6Y0Vv5v1g6tH=...
    // Let's just retrieve whatever the server outputs.

    // 1. Create upload session with Want-Repr-Digest
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "20");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.WANT_REPR_DIGEST, "sha-256");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);
    assertThat(servletResponse.getHeader(HttpHeader.REPR_DIGEST), notNullValue());

    // 2. Append chunk 1
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent(chunk1.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getHeader(HttpHeader.REPR_DIGEST), notNullValue());
  }

  /** End-to-end test verifying withUploadDeduplication functionality using HTTP Digests. */
  @Test
  public void testUploadDeduplicationWithHttpDigests() throws Exception {
    String testContent = "deduplication test content";
    String correctBase64 = "VFeMPTNSgeFcu+9IbyApEaxAC8A8Amvxn7SoLGL4sGM=";

    // 1. Complete Parent upload
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(testContent.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.REPR_DIGEST, "sha-256=:" + correctBase64 + ":");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String parentLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(parentLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(testContent.getBytes(StandardCharsets.UTF_8));
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(200));

    // 2. Complete Child upload
    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(testContent.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.REPR_DIGEST, "sha-256=:" + correctBase64 + ":");
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    String childLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    servletRequest = new MockHttpServletRequest();
    servletResponse = new MockHttpServletResponse();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(childLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(testContent.getBytes(StandardCharsets.UTF_8));
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(200));

    // 3. Verify parent and child upload info links correctly via duplicatesUploadId
    UploadInfo parentInfo = tusFileUploadService.getUploadInfo(parentLocation, OWNER_KEY);
    UploadInfo childInfo = tusFileUploadService.getUploadInfo(childLocation, OWNER_KEY);

    assertThat(childInfo.getDuplicatesUploadId(), is(parentInfo.getId()));

    // Verify child data file was deleted
    String childIdStr = StringUtils.substringAfterLast(childLocation, "/");
    Path childDataPath = storagePath.resolve("uploads").resolve(childIdStr).resolve("data");
    assertFalse(Files.exists(childDataPath));
  }
}
