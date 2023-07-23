package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.PostOnInvalidRequestURIException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class PostUriValidatorTest {

  private PostUriValidator validator;

  private MockHttpServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new PostUriValidator();
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
  public void validateMatchingUrl() throws Exception {
    servletRequest.setRequestURI("/test/upload");
    when(uploadStorageService.getUploadUri()).thenReturn("/test/upload");

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test(expected = PostOnInvalidRequestURIException.class)
  public void validateInvalidUrl() throws Exception {
    servletRequest.setRequestURI("/test/upload/12");
    when(uploadStorageService.getUploadUri()).thenReturn("/test/upload");

    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

    // Expect PostOnInvalidRequestURIException
  }

  @Test
  public void validateMatchingRegexUrl() throws Exception {
    servletRequest.setRequestURI("/users/1234/files/upload");
    when(uploadStorageService.getUploadUri()).thenReturn("/users/[0-9]+/files/upload");

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test(expected = PostOnInvalidRequestURIException.class)
  public void validateInvalidRegexUrl() throws Exception {
    servletRequest.setRequestURI("/users/abc123/files/upload");
    when(uploadStorageService.getUploadUri()).thenReturn("/users/[0-9]+/files/upload");

    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

    // Expect PostOnInvalidRequestURIException
  }

  @Test(expected = PostOnInvalidRequestURIException.class)
  public void validateInvalidRegexUrlPatchUrl() throws Exception {
    servletRequest.setRequestURI("/users/1234/files/upload/7669c72a-3f2a-451f-a3b9-9210e7a4c02f");
    when(uploadStorageService.getUploadUri()).thenReturn("/users/[0-9]+/files/upload");

    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

    // Expect PostOnInvalidRequestURIException
  }
}
