package me.desair.tus.server.upload.s3;

import static org.junit.Assert.assertEquals;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.Test;

public class S3UtilsTest {

  @Test
  public void testParseErrorResponseNull() {
    assertEquals(S3ErrorType.UNKNOWN, S3Utils.parseErrorResponse(null));
  }

  @Test
  public void testParseErrorResponseCodes() throws Exception {
    assertEquals(
        S3ErrorType.NO_SUCH_KEY, S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchKey")));
    assertEquals(
        S3ErrorType.NO_SUCH_KEY,
        S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchBucket")));
    assertEquals(
        S3ErrorType.NO_SUCH_KEY,
        S3Utils.parseErrorResponse(createExceptionWithCode("NoSuchUpload")));
    assertEquals(
        S3ErrorType.PRECONDITION_FAILED,
        S3Utils.parseErrorResponse(createExceptionWithCode("PreconditionFailed")));
    assertEquals(
        S3ErrorType.CONFLICT,
        S3Utils.parseErrorResponse(createExceptionWithCode("ObjectAlreadyExists")));
    assertEquals(
        S3ErrorType.ACCESS_DENIED,
        S3Utils.parseErrorResponse(createExceptionWithCode("AccessDenied")));
    assertEquals(
        S3ErrorType.API_NOT_IMPLEMENTED,
        S3Utils.parseErrorResponse(createExceptionWithCode("APINotImplemented")));
    assertEquals(
        S3ErrorType.API_NOT_IMPLEMENTED,
        S3Utils.parseErrorResponse(createExceptionWithCode("NotImplemented")));
    assertEquals(
        S3ErrorType.UNKNOWN, S3Utils.parseErrorResponse(createExceptionWithCode("InternalError")));
  }

  @Test
  public void testParseErrorResponseHttp501() {
    okhttp3.Response httpResponse =
        new okhttp3.Response.Builder()
            .request(new okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(501)
            .message("Not Implemented")
            .build();
    ErrorResponse errorResponse = org.mockito.Mockito.mock(ErrorResponse.class);
    org.mockito.Mockito.when(errorResponse.code()).thenReturn("");
    ErrorResponseException ex = new ErrorResponseException(errorResponse, httpResponse, null);
    assertEquals(S3ErrorType.API_NOT_IMPLEMENTED, S3Utils.parseErrorResponse(ex));
  }

  private ErrorResponseException createExceptionWithCode(String code) {
    ErrorResponse errorResponse = org.mockito.Mockito.mock(ErrorResponse.class);
    org.mockito.Mockito.when(errorResponse.code()).thenReturn(code);
    return new ErrorResponseException(errorResponse, null, null);
  }
}
