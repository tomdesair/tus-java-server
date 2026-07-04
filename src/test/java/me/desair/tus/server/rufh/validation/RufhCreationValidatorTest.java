package me.desair.tus.server.rufh.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhCreationValidatorTest {

  private RufhCreationValidator validator;
  private MockHttpServletRequest request;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    validator = new RufhCreationValidator();
    request = new MockHttpServletRequest();
  }

  @Test
  public void testSupports() {
    assertTrue(validator.supports(HttpMethod.POST));
    assertTrue(validator.supports(HttpMethod.PUT));
    assertFalse(validator.supports(HttpMethod.GET));
  }

  /**
   * Section 4.1.4 (Limits): "The server might not create an upload resource if the length deduced
   * from the upload creation request is larger than the maximum size."
   */
  @Test(expected = TusException.class)
  public void testValidateUploadLengthExceedsMaxLimit() throws Exception {
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000000");
    when(storageService.getMaxUploadSize()).thenReturn(500000L);

    validator.validate(HttpMethod.POST, request, storageService, null);
  }

  /**
   * Section 4.1.4 (Limits): "The server MAY enforce limits on the size of individual data append
   * requests."
   */
  @Test(expected = TusException.class)
  public void testValidateContentLengthExceedsMaxAppendLimit() throws Exception {
    request.setContent("This payload is long".getBytes());
    when(storageService.getMaxAppendSize()).thenReturn(5L);

    validator.validate(HttpMethod.POST, request, storageService, null);
  }

  @Test
  public void testValidateAcceptableLimits() throws Exception {
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    request.setContent("valid".getBytes());
    when(storageService.getMaxUploadSize()).thenReturn(5000L);
    when(storageService.getMaxAppendSize()).thenReturn(5000L);

    validator.validate(HttpMethod.POST, request, storageService, null);
  }

  @Test
  public void testValidateMaxUploadSizeNullOrZeroOrLengthNull() throws Exception {
    // maxUploadSize is 0
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "1000");
    when(storageService.getMaxUploadSize()).thenReturn(0L);
    validator.validate(HttpMethod.POST, request, storageService, null);

    // uploadLength is null
    request.removeHeader(HttpHeader.UPLOAD_LENGTH);
    when(storageService.getMaxUploadSize()).thenReturn(5000L);
    validator.validate(HttpMethod.POST, request, storageService, null);
  }

  @Test
  public void testValidateMaxAppendSizeNullOrZeroOrContentLengthZero() throws Exception {
    // maxAppendSize is null
    request.setContent("hello".getBytes());
    when(storageService.getMaxAppendSize()).thenReturn(null);
    validator.validate(HttpMethod.POST, request, storageService, null);

    // maxAppendSize is 0
    when(storageService.getMaxAppendSize()).thenReturn(0L);
    validator.validate(HttpMethod.POST, request, storageService, null);

    // contentLength is <= 0
    request.setContent(new byte[0]);
    when(storageService.getMaxAppendSize()).thenReturn(5000L);
    validator.validate(HttpMethod.POST, request, storageService, null);
  }

  @Test
  public void testValidateUploadCompleteConsistency() throws Exception {
    // Upload-Complete true, matching Content-Length
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5");
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("hello".getBytes());
    validator.validate(HttpMethod.POST, request, storageService, null);

    // Upload-Complete true, mismatching Content-Length
    request.setContent("hello world".getBytes()); // 11 bytes vs 5 expected
    try {
      validator.validate(HttpMethod.POST, request, storageService, null);
      org.junit.Assert.fail("Expected TusException for mismatching length");
    } catch (TusException e) {
      assertThat(e.getStatus(), is(400));
    }

    // Upload-Complete false/null
    request.removeHeader(HttpHeader.UPLOAD_COMPLETE);
    validator.validate(HttpMethod.POST, request, storageService, null);

    // Upload-Complete true, but Upload-Length is null
    request.removeHeader(HttpHeader.UPLOAD_LENGTH);
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    request.setContent("hello".getBytes());
    validator.validate(HttpMethod.POST, request, storageService, null);

    // Upload-Complete true, Upload-Length non-null, but Content-Length < 0
    request.addHeader(HttpHeader.UPLOAD_LENGTH, "5");
    MockHttpServletRequest negativeRequest =
        new MockHttpServletRequest() {
          @Override
          public int getContentLength() {
            return -1;
          }

          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    negativeRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "5");
    negativeRequest.addHeader(HttpHeader.UPLOAD_COMPLETE, "?1");
    validator.validate(HttpMethod.POST, negativeRequest, storageService, null);
  }
}
