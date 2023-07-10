package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class UploadLengthValidatorTest {

  private UploadLengthValidator validator;

  private MockHttpServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new UploadLengthValidator();
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
  public void validateNoMaxUploadLength() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(0L);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 300L);

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateBelowMaxUploadLength() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(400L);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 300L);

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateEqualMaxUploadLength() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(300L);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 300L);

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateNoUploadLength() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(300L);
    // servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 300L);

    try {
      validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test(expected = MaxUploadLengthExceededException.class)
  public void validateAboveMaxUploadLength() throws Exception {
    when(uploadStorageService.getMaxUploadSize()).thenReturn(200L);
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 300L);

    validator.validate(HttpMethod.POST, servletRequest, uploadStorageService, null);

    // Expect a MaxUploadLengthExceededException
  }
}
