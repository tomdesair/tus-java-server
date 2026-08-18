package me.desair.tus.server;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.desair.tus.server.upload.UploadInfo;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Abstract base integration test suite for the IETF Resumable Uploads for HTTP (RUFH) protocol.
 *
 * <p>This class contains end-to-end integration test use cases covering the full RUFH lifecycle,
 * structured into clear, step-by-step phases. Concrete subclasses supply the target storage backend
 * by implementing {@link #createTusFileUploadService()}.
 */
public abstract class AbstractITRufhProtocol {

  protected static final String UPLOAD_URI = "/test/upload";
  protected static final String OWNER_KEY = "RUFH_USER";

  protected MockHttpServletRequest servletRequest;
  protected MockHttpServletResponse servletResponse;
  protected TusFileUploadService tusFileUploadService;

  /**
   * Factory method implemented by subclasses to supply a {@link TusFileUploadService} instance
   * configured for a specific storage backend (e.g., Disk, S3, Azure Blob).
   *
   * @return configured TusFileUploadService instance
   * @throws Exception if service creation fails
   */
  protected abstract TusFileUploadService createTusFileUploadService() throws Exception;

  /**
   * Factory method implemented by subclasses to supply a {@link TusFileUploadService} instance
   * configured with a specific upload URI.
   *
   * @param uploadUri The upload URI to configure
   * @return configured TusFileUploadService instance
   * @throws Exception if service creation fails
   */
  protected abstract TusFileUploadService createTusFileUploadService(String uploadUri)
      throws Exception;

  @Before
  public void setUp() throws Exception {
    reset();
    tusFileUploadService = createTusFileUploadService();
  }

  /** Resets mock HTTP request and response objects for a new request step. */
  protected void reset() {
    servletRequest = new MockHttpServletRequest();
    servletRequest.setRemoteAddr("192.168.1.1");
    servletResponse = new MockHttpServletResponse();
  }

  // ===============================================================================================
  // USE CASE 1: OPTIONS Discovery
  // ===============================================================================================

  /**
   * Section 4.1.4 (Limits - Structured Field Format): "Upload-Limit MUST be a Dictionary Structured
   * Header Field..."
   *
   * <p>Use Case: Client sends an OPTIONS request to discover supported features and upload limits.
   */
  @Test
  public void testOptionsDiscovery() throws Exception {
    // Step 1: Send OPTIONS discovery request
    servletRequest.setMethod("OPTIONS");
    servletRequest.setRequestURI(UPLOAD_URI);
    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 204 response with Upload-Limit header and enabled protocol features
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeaderNotBlank(HttpHeader.UPLOAD_LIMIT);

    assertThat(
        tusFileUploadService.getEnabledFeatures(),
        containsInAnyOrder(
            "core",
            "creation",
            "creation-with-upload",
            "checksum",
            "termination",
            "download",
            "expiration",
            "concatenation",
            "cors",
            "resumable-uploads-for-http",
            "http-digests"));
  }

  // ===============================================================================================
  // USE CASE 2: Single-Request Optimistic Upload Creation and Completion
  // ===============================================================================================

  /**
   * Section 4.2.1 & 4.2.2 (Upload Creation - Optimistic Uploads): "If the Upload-Complete request
   * header field is set to true, the client intends to transfer the entire representation data in
   * one request..."
   *
   * <p>Use Case: Client uploads small payload in a single POST request using Upload-Complete: ?1.
   */
  @Test
  public void testOptimisticUploadCreationAndCompletion() throws Exception {
    String payload = "Hello, RUFH Single Request Optimistic Upload!";

    // Step 1: Send single-request optimistic upload via POST with Upload-Complete: ?1
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(payload.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 200 OK response with Upload-Complete: ?1 header
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
  }

  // ===============================================================================================
  // USE CASE 3: Multi-Chunk Resumable Upload Lifecycle
  // ===============================================================================================

  /**
   * Section 4.2 & 4.4 (Resumable Upload Lifecycle): "A client can start a resumable upload... by
   * including the Upload-Complete header field... A server applies a PATCH request with the
   * application/partial-upload media type to append data."
   *
   * <p>Use Case: Create a resumable upload with declared length, append chunk 1, check offset via
   * HEAD, append chunk 2 with Upload-Complete: ?1, and verify downloaded bytes.
   */
  @Test
  public void testResumableUploadMultiChunkLifecycle() throws Exception {
    String part1 = "Part 1 data of resumable upload. ";
    String part2 = "Part 2 final data of upload.";
    long totalLength = part1.length() + part2.length();

    // Step 1: Initiate resumable upload with POST, Upload-Complete: ?0, declared Upload-Length, and
    // initial chunk
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(totalLength));
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.setContent(part1.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 201 Created response, Location header, and initial offset
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length()));
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 3: Query upload progress via HEAD request
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(uploadLocation);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 4: Verify offset and length reported in HEAD response
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length()));
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(totalLength));
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    // Step 5: Append final chunk via PATCH with Upload-Complete: ?1
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(part2.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 6: Verify HTTP 200 OK completing response and final offset
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(totalLength));
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?1");

    // Step 7: Verify internal UploadInfo state reports upload is no longer in progress
    UploadInfo uploadInfo = tusFileUploadService.getUploadInfo(uploadLocation, OWNER_KEY);
    assertFalse(uploadInfo.isUploadInProgress());
    assertThat(uploadInfo.getOffset(), is(totalLength));

    // Step 8: Download uploaded content and verify byte-for-byte matching
    try (InputStream inputStream =
        tusFileUploadService.getUploadedBytes(uploadLocation, OWNER_KEY)) {
      String uploadedContent = IOUtils.toString(inputStream, StandardCharsets.UTF_8);
      assertThat(uploadedContent, is(part1 + part2));
    }
  }

  // ===============================================================================================
  // USE CASE 4: Careful Upload Creation (Empty Creation Request)
  // ===============================================================================================

  /**
   * Section 10.2 (Careful Upload Creation): "A client MAY create a resumable upload resource
   * without uploading any data by sending an empty request with Upload-Complete: ?0."
   *
   * <p>Use Case: Client creates an empty upload resource without payload, then appends data in a
   * subsequent PATCH request.
   */
  @Test
  public void testCarefulUploadCreation() throws Exception {
    // Step 1: Create empty upload resource via POST with Upload-Complete: ?0 and Upload-Length
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 201 Created with Location header and offset 0
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    assertResponseHeaderNotBlank(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "0");
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 3: Append data to the created resource via PATCH at offset 0
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent("Initial data".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 4: Verify HTTP 204 No Content response and updated offset
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "12");
  }

  // ===============================================================================================
  // USE CASE 5: Unknown Length Resumable Upload Lifecycle
  // ===============================================================================================

  /**
   * Section 4.1.3 & 4.4 (Unknown Length Scenario): "If the request does not include the
   * Upload-Length header field, the representation's length is unknown... The representation's
   * length is derived from a completing append."
   *
   * <p>Use Case: Stream chunks when total length is initially unknown, then conclude with a
   * completing append that locks in the final length.
   */
  @Test
  public void testUnknownLengthUploadLifecycle() throws Exception {
    String part1 = "Chunk 1 data. ";
    String part2 = "Chunk 2 data. ";
    String part3 = "Final chunk.";
    long totalLength = part1.length() + part2.length() + part3.length();

    // Step 1: Initiate upload without Upload-Length header
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.setContent(part1.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 201 Created response without Upload-Length header
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length()));
    assertNull(servletResponse.getHeader(HttpHeader.UPLOAD_LENGTH));

    // Step 3: Append second chunk without setting Upload-Complete: ?1
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent(part2.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 4: Verify HTTP 204 No Content response and intermediate offset
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length() + part2.length()));
    assertNull(servletResponse.getHeader(HttpHeader.UPLOAD_LENGTH));

    // Step 5: Send final completing chunk via PATCH with Upload-Complete: ?1
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(
        HttpHeader.UPLOAD_OFFSET, String.valueOf(part1.length() + part2.length()));
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(part3.getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 6: Verify HTTP 200 OK completing response and final total length
    assertResponseStatus(HttpServletResponse.SC_OK);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(totalLength));
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?1");

    // Step 7: Verify HEAD request now returns the derived Upload-Length
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(uploadLocation);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(totalLength));
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(totalLength));
  }

  // ===============================================================================================
  // USE CASE 6: Upload Cancellation / Termination
  // ===============================================================================================

  /**
   * Section 4.5 (Upload Cancellation): "The client can cancel an upload by sending a DELETE request
   * to the upload resource..."
   *
   * <p>Use Case: Create upload, cancel via DELETE request, and verify subsequent HEAD returns 404.
   */
  @Test
  public void testUploadCancellation() throws Exception {
    // Step 1: Create an active upload resource
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 2: Send DELETE request to cancel the upload
    reset();
    servletRequest.setMethod("DELETE");
    servletRequest.setRequestURI(uploadLocation);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);

    // Step 3: Verify resource was deactivated (HEAD returns 404 Not Found)
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(uploadLocation);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  // ===============================================================================================
  // USE CASE 7: Offset Mismatch Detection and Resumption
  // ===============================================================================================

  /**
   * Section 4.4.2 & 7.1 (Mismatching Upload-Offset): "If the Upload-Offset header field value does
   * not match the current offset... the server MUST reject the request with a 409 (Conflict) status
   * code..."
   *
   * <p>Use Case: Send PATCH with wrong offset -> receive 409 Conflict with correct offset header ->
   * resend PATCH with correct offset -> upload succeeds.
   */
  @Test
  public void testOffsetMismatchAndResumption() throws Exception {
    // Step 1: Create upload resource and upload 5 bytes
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.setContent("12345".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "5");

    // Step 2: Attempt PATCH with incorrect offset 0 (server is at offset 5)
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent("6789".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 3: Verify 409 Conflict response containing current server offset (5)
    assertResponseStatus(HttpServletResponse.SC_CONFLICT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "5");

    // Step 4: Resend PATCH with correct offset 5
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "5");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent("6789".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 5: Verify HTTP 204 No Content response and updated offset 9
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "9");
  }

  // ===============================================================================================
  // USE CASE 8: Inconsistent Upload-Length Validation
  // ===============================================================================================

  /**
   * Section 4.1.3 & 7.2 (Inconsistent Upload-Length): "The server MUST reject a request if the
   * representation's length is known and inconsistent..."
   *
   * <p>Use Case: Request declares Upload-Length: 1000 but content is only 11 bytes with
   * Upload-Complete: ?1 -> server rejects with 400 Bad Request.
   */
  @Test
  public void testInconsistentUploadLength() throws Exception {
    // Step 1: Send request declaring Upload-Length: 1000 and Upload-Complete: ?1 but providing only
    // 11 bytes
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    servletRequest.setContent("Hello World".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 2: Verify HTTP 400 Bad Request response
    assertResponseStatus(HttpServletResponse.SC_BAD_REQUEST);
    assertResponseHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
  }

  // ===============================================================================================
  // USE CASE 9: Invalid Append Headers Validation
  // ===============================================================================================

  /**
   * Section 4.4.1 & 4.4.2 (Upload Append Validation): "The request MUST include the Upload-Offset
   * and Upload-Complete header fields. Content-Type MUST be application/partial-upload."
   *
   * <p>Use Case: Send PATCH requests missing required headers or using wrong Content-Type -> server
   * rejects with appropriate HTTP error codes.
   */
  @Test
  public void testInvalidAppendHeaders() throws Exception {
    // Step 1: Create upload resource
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 2: Send PATCH missing Upload-Offset -> verify HTTP 400 Bad Request
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_BAD_REQUEST);

    // Step 3: Send PATCH missing Upload-Complete -> verify HTTP 400 Bad Request
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_BAD_REQUEST);

    // Step 4: Send PATCH with wrong Content-Type (text/plain) -> verify HTTP 415 Unsupported Media
    // Type
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "text/plain");
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE);
  }

  // ===============================================================================================
  // USE CASE 10: Exceeding Upload-Length Rejection & Resource Invalidation
  // ===============================================================================================

  /**
   * Section 4.4.2 (Exceeding Upload-Length): "the server MUST prevent the offset from exceeding the
   * representation's length by rejecting the request with a 409 (Conflict) status code... marking
   * the upload resource invalid."
   *
   * <p>Use Case: Append payload exceeding declared length -> 409 Conflict -> resource invalidation.
   */
  @Test
  public void testExceedingUploadLength() throws Exception {
    // Step 1: Create upload declaring Upload-Length: 10
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 2: Append 15 bytes (exceeding length 10)
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.setContent("123456789012345".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 3: Verify 409 Conflict response
    assertResponseStatus(HttpServletResponse.SC_CONFLICT);

    // Step 4: Verify resource was invalidated (subsequent HEAD returns 404 Not Found)
    reset();
    servletRequest.setMethod("HEAD");
    servletRequest.setRequestURI(uploadLocation);

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_NOT_FOUND);
  }

  // ===============================================================================================
  // USE CASE 11: Content-Digest Validation (RFC 9530)
  // ===============================================================================================

  /**
   * Section 3 of RFC 9530 (Content-Digest): "The Content-Digest HTTP header field associates one or
   * more digests with a message content." If the digest does not match, the server MUST consider
   * the transfer failed.
   *
   * <p>Use Case: Send PATCH payload with invalid sha-256 digest -> rejected with 400 Bad Request;
   * send matching digest -> accepted with 204 No Content.
   */
  @Test
  public void testContentDigestValidation() throws Exception {
    // Step 1: Create upload resource
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI(UPLOAD_URI);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "100");

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);
    assertResponseStatus(HttpServletResponse.SC_CREATED);
    String uploadLocation = servletResponse.getHeader(HttpHeader.LOCATION);

    // Step 2: Send PATCH with wrong sha-256 digest
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(
        HttpHeader.CONTENT_DIGEST, "sha-256=:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=:");
    servletRequest.setContent("hello digest".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 3: Verify HTTP 400 Bad Request rejection due to digest mismatch
    assertResponseStatus(HttpServletResponse.SC_BAD_REQUEST);

    // Step 4: Send PATCH with valid matching sha-256 digest for "hello digest"
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadLocation);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    servletRequest.addHeader(
        HttpHeader.CONTENT_DIGEST, "sha-256=:yV9g7MInOPrtlLDWsplfHK0LaH22Uz70R1ZXbHIjzjU=:");
    servletRequest.setContent("hello digest".getBytes(StandardCharsets.UTF_8));

    tusFileUploadService.process(servletRequest, servletResponse, OWNER_KEY);

    // Step 5: Verify HTTP 204 No Content acceptance and updated offset 12
    assertResponseStatus(HttpServletResponse.SC_NO_CONTENT);
    assertResponseHeader(HttpHeader.UPLOAD_OFFSET, "12");
  }

  @Test
  public void testUploadWithAbsoluteUploadUri() throws Exception {
    String absoluteBaseUri = "https://uploads.example.com";
    TusFileUploadService service = createTusFileUploadService(absoluteBaseUri);

    String uploadContent = "RUFH Absolute URL content";

    // Step 1: POST to create upload on root endpoint "/"
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI("/");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "" + uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    service.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    String locationHeader = servletResponse.getHeader(HttpHeader.LOCATION);
    assertNotNull(locationHeader);

    // Retrieve upload info using the full Location header to verify ID lookup works with absolute
    // URLs
    UploadInfo infoByLocation = service.getUploadInfo(locationHeader, OWNER_KEY);
    assertTrue(infoByLocation != null && infoByLocation.getId() != null);
    assertThat(locationHeader, is("https://uploads.example.com/" + infoByLocation.getId()));

    String uploadPath = "/" + infoByLocation.getId();

    // Step 2: PATCH upload bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadPath);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(uploadContent.getBytes());

    service.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_OK));
    assertThat(
        servletResponse.getHeader(HttpHeader.UPLOAD_OFFSET),
        is("" + uploadContent.getBytes().length));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));

    // Verify upload info is also retrievable via relative path
    UploadInfo infoByPath = service.getUploadInfo(uploadPath, OWNER_KEY);
    assertTrue(infoByPath != null && infoByLocation.getId().equals(infoByPath.getId()));

    // Step 3: Verify content
    try (InputStream stream = service.getUploadedBytes(uploadPath, OWNER_KEY)) {
      assertThat(IOUtils.toString(stream, StandardCharsets.UTF_8), is(uploadContent));
    }
  }

  @Test
  public void testUploadWithAbsoluteUploadUriWithPath() throws Exception {
    String absoluteBaseUri = "https://uploads.example.com/api";
    TusFileUploadService service = createTusFileUploadService(absoluteBaseUri);

    String uploadContent = "RUFH Absolute URL with path content";

    // Step 1: POST to create upload on endpoint "/api"
    servletRequest.setMethod("POST");
    servletRequest.setRequestURI("/api");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "" + uploadContent.getBytes().length);
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");

    service.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_CREATED));
    String locationHeader = servletResponse.getHeader(HttpHeader.LOCATION);
    assertNotNull(locationHeader);

    // Retrieve upload info using the full Location header to verify ID lookup works with absolute
    // URLs
    UploadInfo infoByLocation = service.getUploadInfo(locationHeader, OWNER_KEY);
    assertTrue(infoByLocation != null && infoByLocation.getId() != null);
    assertThat(locationHeader, is("https://uploads.example.com/api/" + infoByLocation.getId()));

    String uploadPath = "/api/" + infoByLocation.getId();

    // Step 2: PATCH upload bytes
    reset();
    servletRequest.setMethod("PATCH");
    servletRequest.setRequestURI(uploadPath);
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    servletRequest.addHeader(HttpHeader.UPLOAD_OFFSET, "0");
    servletRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    servletRequest.setContent(uploadContent.getBytes());

    service.process(servletRequest, servletResponse, OWNER_KEY);
    assertThat(servletResponse.getStatus(), is(HttpServletResponse.SC_OK));
    assertThat(
        servletResponse.getHeader(HttpHeader.UPLOAD_OFFSET),
        is("" + uploadContent.getBytes().length));
    assertThat(servletResponse.getHeader(HttpHeader.UPLOAD_COMPLETE), is("?1"));

    // Verify upload info is also retrievable via relative path
    UploadInfo infoByPath = service.getUploadInfo(uploadPath, OWNER_KEY);
    assertTrue(infoByPath != null && infoByLocation.getId().equals(infoByPath.getId()));

    // Step 3: Verify content
    try (InputStream stream = service.getUploadedBytes(uploadPath, OWNER_KEY)) {
      assertThat(IOUtils.toString(stream, StandardCharsets.UTF_8), is(uploadContent));
    }
  }

  // ===============================================================================================
  // ASSERTION HELPERS
  // ===============================================================================================

  protected void assertResponseStatus(int expectedStatus) {
    assertThat(servletResponse.getStatus(), is(expectedStatus));
  }

  protected void assertResponseHeader(String headerName, String expectedValue) {
    assertThat(servletResponse.getHeader(headerName), is(expectedValue));
  }

  protected void assertResponseHeaderNotBlank(String headerName) {
    assertTrue(
        "Header " + headerName + " should not be blank",
        StringUtils.isNotBlank(servletResponse.getHeader(headerName)));
  }
}
