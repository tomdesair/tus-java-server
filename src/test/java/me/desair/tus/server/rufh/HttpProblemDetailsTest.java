package me.desair.tus.server.rufh;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.util.TusServletResponse;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletResponse;

public class HttpProblemDetailsTest {

  private MockHttpServletResponse response;

  @Before
  public void setUp() {
    response = new MockHttpServletResponse();
  }

  /**
   * Section 7.1 (Mismatching Offset) of draft-ietf-httpbis-resumable-upload-11 & RFC 7807: "This
   * section defines the 'https://iana.org/assignments/http-problem-types#mismatching-upload-offset'
   * problem type. A server can use this problem type when responding to an upload append request
   * (Section 4.4) to indicate that the Upload-Offset header field in the request does not match the
   * upload resource's offset."
   */
  @Test
  public void testOffsetMismatchProblemDetailsObject() throws Exception {
    HttpProblemDetails problem = HttpProblemDetails.forOffsetMismatch(12500000L, 25000000L);

    assertThat(problem.getStatus(), is(409));
    assertThat(
        problem.getType(),
        is("https://iana.org/assignments/http-problem-types#mismatching-upload-offset"));
    assertThat(problem.getTitle(), is("Offset Mismatch"));
    assertThat(
        problem.getDetail(),
        is("The provided Upload-Offset does not match the server's current offset"));
    assertThat(problem.getExtraFields().get("expected-offset"), is(12500000L));

    problem.writeTo(new TusServletResponse(response));

    assertThat(response.getStatus(), is(409));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(
        response.getContentAsString(),
        is(
            "{\"type\":\"https://iana.org/assignments/http-problem-types#mismatching-upload-offset\","
                + "\"title\":\"Offset Mismatch\","
                + "\"status\":409,"
                + "\"detail\":\"The provided Upload-Offset does not match the server's current offset\","
                + "\"expected-offset\":12500000}"));
  }

  /**
   * Section 7.2 (Completed Upload) of draft-ietf-httpbis-resumable-upload-11 & RFC 7807: "This
   * section defines the 'https://iana.org/assignments/http-problem-types#completed-upload' problem
   * type. A server can use this problem type when responding to an upload append request (Section
   * 4.4) to indicate that the upload has already been completed and cannot be modified."
   */
  @Test
  public void testCompletedUploadProblemDetailsObject() throws Exception {
    HttpProblemDetails problem = HttpProblemDetails.forCompletedUpload(400);

    assertThat(problem.getStatus(), is(400));
    assertThat(
        problem.getType(), is("https://iana.org/assignments/http-problem-types#completed-upload"));
    assertThat(problem.getTitle(), is("Upload Completed"));

    problem.writeTo(new TusServletResponse(response));

    assertThat(response.getStatus(), is(400));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(
        response.getContentAsString(),
        is(
            "{\"type\":\"https://iana.org/assignments/http-problem-types#completed-upload\","
                + "\"title\":\"Upload Completed\","
                + "\"status\":400,"
                + "\"detail\":\"The upload resource is already completed and cannot be modified\"}"));
  }

  /**
   * Section 7.3 (Inconsistent Length) of draft-ietf-httpbis-resumable-upload-11 & RFC 7807: "This
   * section defines the
   * 'https://iana.org/assignments/http-problem-types#inconsistent-upload-length' problem type. A
   * server can use this problem type when responding to an upload creation (Section 4.2) or upload
   * append request (Section 4.4) to indicate that the request includes inconsistent upload length
   * values, as described in Section 4.1.3."
   */
  @Test
  public void testInconsistentLengthProblemDetailsObject() throws Exception {
    HttpProblemDetails problem = HttpProblemDetails.forInconsistentLength();

    assertThat(problem.getStatus(), is(400));
    assertThat(
        problem.getType(),
        is("https://iana.org/assignments/http-problem-types#inconsistent-upload-length"));
    assertThat(problem.getTitle(), is("Inconsistent Upload Length"));

    problem.writeTo(new TusServletResponse(response));

    assertThat(response.getStatus(), is(400));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(
        response.getContentAsString(),
        is(
            "{\"type\":\"https://iana.org/assignments/http-problem-types#inconsistent-upload-length\","
                + "\"title\":\"Inconsistent Upload Length\","
                + "\"status\":400,"
                + "\"detail\":\"The provided Upload-Length does not match existing metadata\"}"));
  }

  @Test
  public void testProblemDetailsOfAndSendHelpers() throws Exception {
    HttpProblemDetails.sendProblemDetails(
        new TusServletResponse(response), 418, "type-uri", "Teapot Title", "Teapot Detail", null);
    assertThat(response.getStatus(), is(418));
    assertThat(response.getContentAsString(), containsString("\"title\":\"Teapot Title\""));

    response = new MockHttpServletResponse();
    HttpProblemDetails.sendOffsetMismatch(new TusServletResponse(response), 100L, 200L);
    assertThat(response.getStatus(), is(409));

    response = new MockHttpServletResponse();
    HttpProblemDetails.sendCompletedUpload(new TusServletResponse(response), 400);
    assertThat(response.getStatus(), is(400));

    response = new MockHttpServletResponse();
    HttpProblemDetails.sendInconsistentLength(new TusServletResponse(response));
    assertThat(response.getStatus(), is(400));
  }

  @Test
  public void testWriteToStandardHttpServletResponse() throws Exception {
    HttpProblemDetails problem = HttpProblemDetails.forCompletedUpload(400);
    problem.writeTo(response);
    assertThat(response.getStatus(), is(400));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(response.getContentAsString(), containsString("completed-upload"));
  }

  @Test
  public void testNullTypeAndDetail() throws Exception {
    HttpProblemDetails problem = new HttpProblemDetails(500, null, "Title", null, null);
    assertThat(problem.getType(), is("about:blank"));
    assertThat(problem.getDetail(), is(org.hamcrest.CoreMatchers.nullValue()));
    assertThat(
        problem.toJson(), is("{\"type\":\"about:blank\",\"title\":\"Title\",\"status\":500}"));
  }

  @Test
  public void testJsonEscapingNullKeyAndSpecialChars() throws Exception {
    java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
    extra.put(null, "val-with-\\-\"-controls-\n-\r-\t");
    HttpProblemDetails problem = new HttpProblemDetails(500, "type", "Title", "Detail", extra);
    String json = problem.toJson();
    // Null key escapes to empty string ""
    assertThat(json, containsString("\"\":"));
    // Escaped backslash \\
    assertThat(json, containsString("\\\\"));
    // Escaped quote \"
    assertThat(json, containsString("\\\""));
    // Escaped controls
    assertThat(json, containsString("\\n"));
    assertThat(json, containsString("\\r"));
    assertThat(json, containsString("\\t"));
  }

  @Test
  public void testEmptyExtraFieldsAndBooleanValue() throws Exception {
    // empty extraFields
    HttpProblemDetails problem1 =
        new HttpProblemDetails(500, "type", "Title", "Detail", new java.util.LinkedHashMap<>());
    assertThat(
        problem1.toJson(),
        is("{\"type\":\"type\",\"title\":\"Title\",\"status\":500,\"detail\":\"Detail\"}"));

    // boolean value in extraFields
    java.util.Map<String, Object> extra = new java.util.LinkedHashMap<>();
    extra.put("active", true);
    HttpProblemDetails problem2 = new HttpProblemDetails(500, "type", "Title", "Detail", extra);
    assertThat(
        problem2.toJson(),
        is(
            "{\"type\":\"type\",\"title\":\"Title\",\"status\":500,\"detail\":\"Detail\",\"active\":true}"));
  }
}
