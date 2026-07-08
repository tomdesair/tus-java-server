package me.desair.tus.server.creation.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.InvalidUploadLengthException;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class PatchUploadLengthValidatorTest {

  private PatchUploadLengthValidator validator;
  private MockHttpServletRequest servletRequest;
  private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new PatchUploadLengthValidator();
    uploadStorageService = mock(UploadStorageService.class);
  }

  @Test
  public void supports() {
    assertThat(validator.supports(HttpMethod.PATCH), is(true));
    assertThat(validator.supports(HttpMethod.POST), is(false));
    assertThat(validator.supports(HttpMethod.GET), is(false));
  }

  @Test
  public void validateNoHeader() throws Exception {
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
  }

  @Test(expected = InvalidUploadLengthException.class)
  public void validateNegativeLength() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, -100L);
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
  }

  @Test
  public void validateFirstTimeSuccess() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 1000L);
    servletRequest.setRequestURI("/files/123");

    UploadInfo info = new UploadInfo();
    // length is null (deferred)
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);
    when(uploadStorageService.getMaxUploadSize()).thenReturn(2000L);

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, "owner");
  }

  @Test(expected = MaxUploadLengthExceededException.class)
  public void validateFirstTimeExceedsMax() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 3000L);
    servletRequest.setRequestURI("/files/123");

    UploadInfo info = new UploadInfo();
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);
    when(uploadStorageService.getMaxUploadSize()).thenReturn(2000L);

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, "owner");
  }

  @Test
  public void validateMatchingLength() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 1000L);
    servletRequest.setRequestURI("/files/123");

    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, "owner");
  }

  @Test(expected = InvalidUploadLengthException.class)
  public void validateModifiedLength() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_LENGTH, 1001L);
    servletRequest.setRequestURI("/files/123");

    UploadInfo info = new UploadInfo();
    info.setLength(1000L);
    when(uploadStorageService.getUploadInfo("/files/123", "owner")).thenReturn(info);

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, "owner");
  }
}
