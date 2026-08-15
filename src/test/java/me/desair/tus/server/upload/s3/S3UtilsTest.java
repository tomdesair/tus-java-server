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
        S3ErrorType.UNKNOWN, S3Utils.parseErrorResponse(createExceptionWithCode("InternalError")));
  }

  private ErrorResponseException createExceptionWithCode(String code) {
    ErrorResponse errorResponse = org.mockito.Mockito.mock(ErrorResponse.class);
    org.mockito.Mockito.when(errorResponse.code()).thenReturn(code);
    return new ErrorResponseException(errorResponse, null, null);
  }
}
