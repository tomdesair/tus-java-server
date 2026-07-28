package me.desair.tus.server.rufh.handler;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.InconsistentUploadLengthException;
import me.desair.tus.server.exception.UploadAlreadyCompletedException;
import me.desair.tus.server.exception.UploadOffsetMismatchException;
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
public class RufhErrorHandlerTest {

  private RufhErrorHandler handler;
  private MockHttpServletRequest request;
  private MockHttpServletResponse response;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    handler = new RufhErrorHandler();
    request = new MockHttpServletRequest();
    response = new MockHttpServletResponse();
  }

  @Test
  public void testSupports() {
    assertTrue(handler.supports(HttpMethod.PATCH));
    assertTrue(handler.supports(HttpMethod.POST));
  }

  @Test
  public void testIsErrorHandler() {
    assertTrue(handler.isErrorHandler());
  }

  /**
   * Section 8 (Offset Mismatch): "If the Upload-Offset request header field value does not match
   * the current offset... the server MUST respond with a 409 Conflict status code and an
   * application/problem+json response body including the expected-offset member."
   */
  @Test
  public void testProcessErrorHandler409Mismatch() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("mismatch-id"));
    info.setOffset(1000L); // Server offset is 1000
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(info);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(409));
    assertThat(response.getHeader(HttpHeader.CONTENT_TYPE), is("application/problem+json"));
    assertThat(response.getContentAsString(), containsString("\"expected-offset\":1000"));
  }

  @Test
  public void testProcessErrorHandler409MismatchNullUploadInfoOrOffset() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    // No UPLOAD_OFFSET header
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(null);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(409));
    assertThat(response.getContentAsString(), containsString("\"expected-offset\":0"));
  }

  @Test
  public void testProcessErrorHandler400CompletedUpload() throws Exception {
    request.setRequestURI("/files/completed-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("completed-id"));
    info.setOffset(5000L);
    info.setLength(5000L); // Completed
    when(storageService.getUploadInfo("/files/completed-id", "owner")).thenReturn(info);

    UploadAlreadyCompletedException exception = new UploadAlreadyCompletedException("Completed");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(400));
    assertThat(response.getContentAsString(), containsString("completed-upload"));
  }

  @Test
  public void testProcessErrorHandler400InconsistentLength() throws Exception {
    request.setRequestURI("/files/in-progress-id");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("in-progress-id"));
    info.setOffset(1000L);
    info.setLength(5000L); // In progress
    when(storageService.getUploadInfo("/files/in-progress-id", "owner")).thenReturn(info);

    InconsistentUploadLengthException exception =
        new InconsistentUploadLengthException("Inconsistent");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(400));
    assertThat(response.getContentAsString(), containsString("inconsistent-upload-length"));
  }

  @Test
  public void testProcessErrorHandler409MismatchNullOffset() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("mismatch-id"));
    info.setOffset(null); // Null offset
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(info);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(409));
    assertThat(response.getContentAsString(), containsString("\"expected-offset\":0"));
  }

  @Test
  public void testProcessErrorHandler400NullUploadInfo() throws Exception {
    request.setRequestURI("/files/null-id");
    when(storageService.getUploadInfo("/files/null-id", "owner")).thenReturn(null);

    InconsistentUploadLengthException exception =
        new InconsistentUploadLengthException("Inconsistent");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);
    if (problem != null) {
      problem.writeTo(new TusServletResponse(response));
    }

    assertThat(response.getStatus(), is(400));
    assertThat(response.getContentAsString(), containsString("inconsistent-upload-length"));
  }

  @Test
  public void testProcessWithUploadOffsetMismatchException() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("mismatch-id"));
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(info);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch!");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);

    assertThat(problem.getStatus(), is(409));
    assertThat(problem.getType(), containsString("mismatching-upload-offset"));
    assertThat(problem.getExtraFields().get("expected-offset"), is(1000L));
  }

  @Test
  public void testProcessWithUploadAlreadyCompletedException() throws Exception {
    UploadAlreadyCompletedException exception = new UploadAlreadyCompletedException("Completed!");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);

    assertThat(problem.getStatus(), is(400));
    assertThat(problem.getType(), containsString("completed-upload"));
  }

  @Test
  public void testProcessWithInconsistentUploadLengthException() throws Exception {
    InconsistentUploadLengthException exception =
        new InconsistentUploadLengthException("Inconsistent!");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);

    assertThat(problem.getStatus(), is(400));
    assertThat(problem.getType(), containsString("inconsistent-upload-length"));
  }

  @Test
  public void testProcessWithUploadOffsetMismatchExceptionNullInfo() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(null);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch!");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);

    assertThat(problem.getStatus(), is(409));
    assertThat(problem.getExtraFields().get("expected-offset"), is(0L));
  }

  @Test
  public void testProcessWithUploadOffsetMismatchExceptionNullOffset() throws Exception {
    request.setRequestURI("/files/mismatch-id");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000");

    UploadInfo info = new UploadInfo();
    info.setId(new UploadId("mismatch-id"));
    info.setOffset(null);
    when(storageService.getUploadInfo("/files/mismatch-id", "owner")).thenReturn(info);

    UploadOffsetMismatchException exception = new UploadOffsetMismatchException("Mismatch!");

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            exception);

    assertThat(problem.getStatus(), is(409));
    assertThat(problem.getExtraFields().get("expected-offset"), is(0L));
  }

  @Test
  public void testProcessWithNonErrorStatus() throws Exception {
    response.setStatus(200);

    HttpProblemDetails problem =
        handler.process(
            HttpMethod.PATCH,
            new TusServletRequest(request),
            new TusServletResponse(response),
            storageService,
            null,
            "owner",
            null);

    // problem is null, so response body should remain empty
    assertThat(problem, org.hamcrest.CoreMatchers.nullValue());
    assertThat(response.getContentAsString(), is(""));
  }
}
