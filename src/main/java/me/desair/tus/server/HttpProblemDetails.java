package me.desair.tus.server;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Value object representing RFC 7807 Problem Details for Resumable Uploads for HTTP (RUFH),
 * specified in Section 8 of draft-ietf-httpbis-resumable-upload.
 *
 * <p>Provides structured data modeling and JSON formatting for problem detail responses.
 */
public class HttpProblemDetails {

  private final int status;
  private final String type;
  private final String title;
  private final String detail;
  private final Map<String, Object> extraFields;

  /**
   * Construct an immutable RFC 7807 problem details instance.
   *
   * @param status HTTP response status code
   * @param type Problem type URI reference (defaults to "about:blank" if null)
   * @param title Short summary of the problem type
   * @param detail Human-readable explanation specific to this occurrence of the problem
   * @param extraFields Additional extension members (e.g. expected-offset)
   */
  public HttpProblemDetails(
      int status, String type, String title, String detail, Map<String, Object> extraFields) {
    this.status = status;
    this.type = type != null ? type : "about:blank";
    this.title = title;
    this.detail = detail;
    this.extraFields =
        extraFields != null
            ? Collections.unmodifiableMap(new LinkedHashMap<>(extraFields))
            : Collections.emptyMap();
  }

  /**
   * Factory method to create a problem details instance.
   *
   * @param status HTTP response status code
   * @param type Problem type URI reference
   * @param title Short summary of the problem type
   * @param detail Human-readable explanation
   * @param extraFields Additional extension members
   * @return A new HttpProblemDetails instance
   */
  public static HttpProblemDetails of(
      int status, String type, String title, String detail, Map<String, Object> extraFields) {
    return new HttpProblemDetails(status, type, title, detail, extraFields);
  }

  /**
   * Create an Offset Mismatch (409 Conflict) problem details response for RUFH append requests.
   *
   * <p>Reference: Section 7.1 (Mismatching Offset) of draft-ietf-httpbis-resumable-upload-11: "This
   * section defines the 'https://iana.org/assignments/http-problem-types#mismatching-upload-offset'
   * problem type."
   *
   * @param expectedOffset Expected server byte offset
   * @param providedOffset Provided Upload-Offset header value
   * @return HttpProblemDetails instance configured for offset mismatch
   */
  public static HttpProblemDetails forOffsetMismatch(long expectedOffset, Long providedOffset) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("expected-offset", expectedOffset);
    return new HttpProblemDetails(
        HttpServletResponse.SC_CONFLICT,
        "https://iana.org/assignments/http-problem-types#mismatching-upload-offset",
        "Offset Mismatch",
        "The provided Upload-Offset does not match the server's current offset",
        extra);
  }

  /**
   * Create an Upload Completed problem details response.
   *
   * <p>Reference: Section 7.2 (Completed Upload) of draft-ietf-httpbis-resumable-upload-11: "This
   * section defines the 'https://iana.org/assignments/http-problem-types#completed-upload' problem
   * type."
   *
   * @param status HTTP response status code (e.g. 400 Bad Request)
   * @return HttpProblemDetails instance configured for completed upload
   */
  public static HttpProblemDetails forCompletedUpload(int status) {
    return new HttpProblemDetails(
        status,
        "https://iana.org/assignments/http-problem-types#completed-upload",
        "Upload Completed",
        "The upload resource is already completed and cannot be modified",
        null);
  }

  /**
   * Create an Inconsistent Length (400 Bad Request) problem details response.
   *
   * <p>Reference: Section 7.3 (Inconsistent Length) of draft-ietf-httpbis-resumable-upload-11:
   * "This section defines the
   * 'https://iana.org/assignments/http-problem-types#inconsistent-upload-length' problem type."
   *
   * @return HttpProblemDetails instance configured for inconsistent upload length
   */
  public static HttpProblemDetails forInconsistentLength() {
    return new HttpProblemDetails(
        HttpServletResponse.SC_BAD_REQUEST,
        "https://iana.org/assignments/http-problem-types#inconsistent-upload-length",
        "Inconsistent Upload Length",
        "The provided Upload-Length does not match existing metadata",
        null);
  }

  /**
   * Create a Mismatched Digest Values (400 Bad Request) problem details response.
   *
   * <p>Reference: Section 4 of RFC 9530: "This section defines the
   * 'https://iana.org/assignments/http-problem-types#digest-mismatched-values' problem type."
   *
   * @return HttpProblemDetails instance configured for digest mismatch
   */
  public static HttpProblemDetails forDigestMismatch() {
    return new HttpProblemDetails(
        HttpServletResponse.SC_BAD_REQUEST,
        "https://iana.org/assignments/http-problem-types#digest-mismatched-values",
        "Mismatched Digest Values",
        "The calculated digest does not match the provided digest value.",
        null);
  }

  /**
   * Send problem details response directly to a TusServletResponse.
   *
   * @param response The servlet response
   * @param status HTTP response status code
   * @param type Problem type URI reference
   * @param title Short summary
   * @param detail Human-readable explanation
   * @param extraFields Additional extension members
   * @throws IOException When writing response fails
   */
  public static void sendProblemDetails(
      TusServletResponse response,
      int status,
      String type,
      String title,
      String detail,
      Map<String, Object> extraFields)
      throws IOException {
    of(status, type, title, detail, extraFields).writeTo(response);
  }

  /**
   * Helper to send offset mismatch problem details directly.
   *
   * @param response Servlet response
   * @param expectedOffset Expected server offset
   * @param providedOffset Provided upload offset
   * @throws IOException When writing response fails
   */
  public static void sendOffsetMismatch(
      TusServletResponse response, long expectedOffset, Long providedOffset) throws IOException {
    forOffsetMismatch(expectedOffset, providedOffset).writeTo(response);
  }

  /**
   * Helper to send completed upload problem details directly.
   *
   * @param response Servlet response
   * @param status HTTP status code
   * @throws IOException When writing response fails
   */
  public static void sendCompletedUpload(TusServletResponse response, int status)
      throws IOException {
    forCompletedUpload(status).writeTo(response);
  }

  /**
   * Helper to send inconsistent length problem details directly.
   *
   * @param response Servlet response
   * @throws IOException When writing response fails
   */
  public static void sendInconsistentLength(TusServletResponse response) throws IOException {
    forInconsistentLength().writeTo(response);
  }

  /**
   * Serialize this problem details instance to a JSON string according to RFC 7807.
   *
   * @return Valid JSON string representation
   */
  public String toJson() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("type", type);
    map.put("title", title);
    map.put("status", status);
    if (detail != null) {
      map.put("detail", detail);
    }
    if (!extraFields.isEmpty()) {
      map.putAll(extraFields);
    }
    return formatJson(map);
  }

  /**
   * Write this problem details instance to the HTTP servlet response.
   *
   * @param response Target HttpServletResponse
   * @throws IOException When writing to response stream fails
   */
  public void writeTo(HttpServletResponse response) throws IOException {
    Objects.requireNonNull(response, "Response cannot be null");
    response.setStatus(status);
    response.setHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PROBLEM_JSON);
    response.getWriter().write(toJson());
    response.getWriter().flush();
  }

  /**
   * Write this problem details instance to the TusServletResponse wrapper.
   *
   * @param response Target TusServletResponse
   * @throws IOException When writing to response stream fails
   */
  public void writeTo(TusServletResponse response) throws IOException {
    Objects.requireNonNull(response, "Response cannot be null");
    response.setStatus(status);
    response.setHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PROBLEM_JSON);
    response.getWriter().write(toJson());
    response.getWriter().flush();
  }

  public int getStatus() {
    return status;
  }

  public String getType() {
    return type;
  }

  public String getTitle() {
    return title;
  }

  public String getDetail() {
    return detail;
  }

  public Map<String, Object> getExtraFields() {
    return extraFields;
  }

  private String formatJson(Map<String, Object> map) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      if (!first) {
        sb.append(",");
      }
      first = false;
      sb.append("\"").append(escapeJsonString(entry.getKey())).append("\":");
      Object value = entry.getValue();
      if (value instanceof Number || value instanceof Boolean) {
        sb.append(value);
      } else {
        sb.append("\"").append(escapeJsonString(String.valueOf(value))).append("\"");
      }
    }
    sb.append("}");
    return sb.toString();
  }

  private String escapeJsonString(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
