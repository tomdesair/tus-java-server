package me.desair.tus.server.rufh;

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
}
