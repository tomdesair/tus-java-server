package me.desair.tus.server.core.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidTusResumableException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class TusResumableValidatorTest {

  private MockHttpServletRequest servletRequest;
  private TusResumableValidator validator;
  private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new TusResumableValidator();
  }

  @Test(expected = InvalidTusResumableException.class)
  public void validateNoVersion() throws Exception {
    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
  }

  @Test(expected = InvalidTusResumableException.class)
  public void validateInvalidVersion() throws Exception {
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "2.0.0");
    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
  }

  @Test
  public void validateValid() throws Exception {
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }
  }

  @Test
  public void validateNullMethod() throws Exception {
    servletRequest.addHeader(HttpHeader.TUS_RESUMABLE, "1.0.0");
    try {
      validator.validate(null, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }
  }

  @Test
  public void supports() throws Exception {
    assertThat(validator.supports(HttpMethod.GET), is(false));
    assertThat(validator.supports(HttpMethod.POST), is(true));
    assertThat(validator.supports(HttpMethod.PUT), is(true));
    assertThat(validator.supports(HttpMethod.DELETE), is(true));
    assertThat(validator.supports(HttpMethod.HEAD), is(true));
    assertThat(validator.supports(HttpMethod.OPTIONS), is(false));
    assertThat(validator.supports(HttpMethod.PATCH), is(true));
    assertThat(validator.supports(null), is(true));
  }
}
