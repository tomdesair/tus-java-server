package me.desair.tus.server.concatenation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UploadLengthNotAllowedOnConcatenationException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class NoUploadLengthOnFinalValidatorTest {

  private NoUploadLengthOnFinalValidator validator;

  private MockHttpServletRequest servletRequest;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new NoUploadLengthOnFinalValidator();
  }

  @Test
  public void supports() throws Exception {
    assertThat(validator.supports(HttpMethod.GET), is(false));
    assertThat(validator.supports(HttpMethod.POST), is(true));
    assertThat(validator.supports(HttpMethod.PUT), is(false));
    assertThat(validator.supports(HttpMethod.DELETE), is(false));
    assertThat(validator.supports(HttpMethod.HEAD), is(false));
    assertThat(validator.supports(HttpMethod.OPTIONS), is(false));
    assertThat(validator.supports(HttpMethod.PATCH), is(false));
    assertThat(validator.supports(null), is(false));
  }

  @Test
  public void validateFinalUploadValid() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "final;12345 235235 253523");
    // servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10L");

    // When we validate the request
    try {
      validator.validate(HttpMethod.POST, servletRequest, null, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test(expected = UploadLengthNotAllowedOnConcatenationException.class)
  public void validateFinalUploadInvalid() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "final;12345 235235 253523");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10L");

    // When we validate the request
    validator.validate(HttpMethod.POST, servletRequest, null, null);
  }

  @Test
  public void validateNotFinal1() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10L");

    // When we validate the request
    try {
      validator.validate(HttpMethod.POST, servletRequest, null, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateNotFinal2() throws Exception {
    // servletRequest.addHeader(HttpHeader.UPLOAD_CONCAT, "partial");
    // servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, "10L");

    // When we validate the request
    try {
      validator.validate(HttpMethod.POST, servletRequest, null, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }
}
