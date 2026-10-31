package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidUploadMetadataException;
import me.desair.tus.server.exception.TusException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class UploadMetadataValidatorTest {

  private UploadMetadataValidator validator;
  private MockHttpServletRequest servletRequest;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new UploadMetadataValidator();
  }

  @Test
  public void supports() {
    assertThat(validator.supports(HttpMethod.POST), is(true));
    assertThat(validator.supports(HttpMethod.PATCH), is(false));
  }

  @Test
  public void validateNoHeader() throws Exception {
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test
  public void validateValidMetadata() throws Exception {
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbi5wZGY=,is_confidential");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test(expected = TusException.class)
  public void validateEmptyPair() throws Exception {
    servletRequest.addHeader(
        HttpHeader.UPLOAD_METADATA, "filename d29ybGRfZG9taW5hdGlvbi5wZGY=,,is_confidential");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test(expected = TusException.class)
  public void validateMultipleSpaces() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename  d29ybGRfZG9taW5hdGlvbi5wZGY=");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test(expected = TusException.class)
  public void validateInvalidBase64() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "filename invalid%base64");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test(expected = InvalidUploadMetadataException.class)
  public void validateOversizedHeaderThrowsException() throws Exception {
    // Generate an Upload-Metadata header larger than 65536 bytes to prevent DoS memory spikes
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 7000; i++) {
      sb.append("k").append(i).append(" dmFsdWU=,");
    }
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, sb.toString());
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test
  public void validateNonAsciiKeyIsAllowed() throws Exception {
    // Non-ASCII characters in key (e.g. ñ) are permitted for backward compatibility
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "fileñame d29ybGQ=");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
    // KISS: verifying method executes cleanly without throwing an exception
    org.junit.Assert.assertTrue(true);
  }

  @Test(expected = InvalidUploadMetadataException.class)
  public void validateKeyWithWhitespaceThrowsException() throws Exception {
    // Key containing spaces or whitespace is invalid
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "file name d29ybGQ=");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test(expected = InvalidUploadMetadataException.class)
  public void validateKeyWithTabWhitespaceThrowsException() throws Exception {
    // Key containing tab whitespace fails isValidKey check
    servletRequest.addHeader(HttpHeader.UPLOAD_METADATA, "file\tname d29ybGQ=");
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }
}
