package me.desair.tus.server.core.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ContentLengthValidatorTest {

  private ContentLengthValidator validator;

  private MockHttpServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new ContentLengthValidator();
  }

  @Test
  public void validateValidLengthInitialUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(0L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 10L);

    // When we validate the request
    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateValidLengthInProgressUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(5L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 5L);

    // When we validate the request
    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateValidLengthPartialUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 3L);

    // When we validate the request
    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test(expected = InvalidContentLengthException.class)
  public void validateInvalidLengthInitialUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(0L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 11L);

    // When we validate the request
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

    // Then expect a InvalidContentLengthException exception
  }

  @Test(expected = InvalidContentLengthException.class)
  public void validateInvalidLengthInProgressUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(5L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 6L);

    // When we validate the request
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

    // Then expect a InvalidContentLengthException exception
  }

  @Test(expected = InvalidContentLengthException.class)
  public void validateInvalidLengthPartialUpload() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 10L);

    // When we validate the request
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

    // Then expect a InvalidContentLengthException exception
  }

  @Test
  public void validateMissingContentLength() throws Exception {
    UploadInfo info = new UploadInfo();
    info.setOffset(2L);
    info.setLength(10L);
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(info);

    // We don't set a content length header
    // servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 3L);

    // When we validate the request
    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
  }

  @Test
  public void validateMissingUploadInfo() throws Exception {
    when(uploadStorageService.getUploadInfo(nullable(String.class), nullable(String.class)))
        .thenReturn(null);

    servletRequest.addHeader(HttpHeader.CONTENT_LENGTH, 3L);

    // When we validate the request
    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No Exception is thrown
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
