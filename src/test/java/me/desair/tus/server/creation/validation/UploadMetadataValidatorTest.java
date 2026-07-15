package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
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
}
