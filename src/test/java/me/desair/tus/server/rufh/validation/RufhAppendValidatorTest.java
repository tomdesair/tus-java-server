package me.desair.tus.server.rufh.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhAppendValidatorTest {

  private RufhAppendValidator validator;
  private MockHttpServletRequest request;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    validator = new RufhAppendValidator();
    request = new MockHttpServletRequest();
  }

  @Test
  public void testSupports() {
    assertTrue(validator.supports(HttpMethod.PATCH));
    assertFalse(validator.supports(HttpMethod.POST));
  }

  /**
   * Section 5.1 (Append Request): "The request MUST indicate the offset of the request content
   * inside the representation data by including the Upload-Offset header field."
   */
  @Test(expected = TusException.class)
  public void testValidateMissingUploadOffsetHeader() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  /**
   * Section 5.2 (Server Behavior - Offset Mismatch): "If the Upload-Offset request header field
   * value does not match the current offset... the upload resource MUST reject the request with a
   * 409 (Conflict) status code."
   */
  @Test(expected = TusException.class)
  public void testValidateMismatchingUploadOffsetHeader() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "2000"); // Current offset is 1000

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  /**
   * Section 5.2 (Server Behavior - Completed Upload Reject): "If the upload is already complete...
   * the server MUST NOT modify the upload resource and MUST reject the request."
   */
  @Test(expected = TusException.class)
  public void testValidateCompletedUploadRejected() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "5000");

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(5000L); // Completed
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateValidAppendRequest() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateUploadInfoNull() throws Exception {
    request.setRequestURI("/files/does-not-exist");
    when(storageService.getUploadInfo("/files/does-not-exist", "owner")).thenReturn(null);
    // Should return early and not throw any exception
    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test(expected = TusException.class)
  public void testValidateMismatchingUploadLength() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "6000"); // Current length is 5000

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateValidAppendRequestAlternativeContentType() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, "application/offset+octet-stream");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test(expected = TusException.class)
  public void testValidateInvalidContentType() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, "text/plain");
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateMaxAppendSizeNullOrZeroOrContentLengthZero() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    // maxAppendSize is null
    when(storageService.getMaxAppendSize()).thenReturn(null);
    request.setContent("hello".getBytes());
    validator.validate(HttpMethod.PATCH, request, storageService, "owner");

    // maxAppendSize is 0
    when(storageService.getMaxAppendSize()).thenReturn(0L);
    validator.validate(HttpMethod.PATCH, request, storageService, "owner");

    // contentLength is 0
    when(storageService.getMaxAppendSize()).thenReturn(5000L);
    request.setContent(new byte[0]);
    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateUploadInfoNoLengthButProvidedLength() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000"); // Provided length in request

    UploadInfo info = new UploadInfo();
    info.setLength(null); // Upload info has no length yet
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }

  @Test
  public void testValidateMatchingUploadLength() throws Exception {
    request.setRequestURI("/files/exists");
    request.addHeader(HttpHeader.CONTENT_TYPE, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD);
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "1000");
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5000"); // Matches the existing upload length

    UploadInfo info = new UploadInfo();
    info.setLength(5000L);
    info.setOffset(1000L);
    when(storageService.getUploadInfo("/files/exists", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, request, storageService, "owner");
  }
}
