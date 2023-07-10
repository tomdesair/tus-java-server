package me.desair.tus.server.core.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidContentTypeException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class ContentTypeValidatorTest {

  private ContentTypeValidator validator;

  private MockHttpServletRequest servletRequest;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new ContentTypeValidator();
  }

  @Test
  public void validateValid() throws Exception {
    servletRequest.addHeader(
        HttpHeader.CONTENT_TYPE, ContentTypeValidator.APPLICATION_OFFSET_OCTET_STREAM);

    try {
      validator.validate(HttpMethod.PATCH, servletRequest, null, null);
    } catch (Exception ex) {
      fail();
    }

    // No exception is thrown
  }

  @Test(expected = InvalidContentTypeException.class)
  public void validateInvalidHeader() throws Exception {
    servletRequest.addHeader(HttpHeader.CONTENT_TYPE, "application/octet-stream");

    validator.validate(HttpMethod.PATCH, servletRequest, null, null);

    // Expect a InvalidContentTypeException exception
  }

  @Test(expected = InvalidContentTypeException.class)
  public void validateMissingHeader() throws Exception {
    // We don't set the header
    // servletRequest.addHeader(HttpHeader.CONTENT_TYPE,
    // ContentTypeValidator.APPLICATION_OFFSET_OCTET_STREAM);

    validator.validate(HttpMethod.PATCH, servletRequest, null, null);

    // Expect a InvalidContentTypeException exception
  }

  @Test
  public void supports() throws Exception {
    assertThat(validator.supports(HttpMethod.GET), is(false));
    assertThat(validator.supports(HttpMethod.POST), is(false));
    assertThat(validator.supports(HttpMethod.PUT), is(false));
    assertThat(validator.supports(HttpMethod.DELETE), is(false));
    assertThat(validator.supports(HttpMethod.HEAD), is(false));
    assertThat(validator.supports(HttpMethod.OPTIONS), is(false));
    assertThat(validator.supports(HttpMethod.PATCH), is(true));
    assertThat(validator.supports(null), is(false));
  }
}
