package me.desair.tus.server.concatenation.validation;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

import java.util.UUID;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.PatchOnFinalUploadNotAllowedException;
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class PatchFinalUploadValidatorTest {

  private PatchFinalUploadValidator validator;

  private MockHttpServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = new MockHttpServletRequest();
    validator = new PatchFinalUploadValidator();
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

  @Test
  public void testValid() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadType(UploadType.REGULAR);

    UploadInfo info2 = new UploadInfo();
    info2.setId(new UploadId(UUID.randomUUID()));
    info2.setUploadType(UploadType.PARTIAL);

    UploadInfo info3 = new UploadInfo();
    info3.setId(new UploadId(UUID.randomUUID()));
    info3.setUploadType(null);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);
    when(uploadStorageService.getUploadInfo(eq(info2.getId().toString()), nullable(String.class)))
        .thenReturn(info2);
    when(uploadStorageService.getUploadInfo(eq(info3.getId().toString()), nullable(String.class)))
        .thenReturn(info3);

    // When we validate the requests
    try {
      servletRequest.setRequestURI(info1.getId().toString());
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

      servletRequest.setRequestURI(info2.getId().toString());
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

      servletRequest.setRequestURI(info3.getId().toString());
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }

    // No exception is thrown
  }

  @Test
  public void testValidNotFound() throws Exception {
    try {
      // When we validate the request
      servletRequest.setRequestURI("/upload/test");
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }
  }

  @Test(expected = PatchOnFinalUploadNotAllowedException.class)
  public void testInvalidFinal() throws Exception {
    UploadInfo info1 = new UploadInfo();
    info1.setId(new UploadId(UUID.randomUUID()));
    info1.setUploadType(UploadType.CONCATENATED);

    when(uploadStorageService.getUploadInfo(eq(info1.getId().toString()), nullable(String.class)))
        .thenReturn(info1);

    // When we validate the request
    servletRequest.setRequestURI(info1.getId().toString());
    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
  }
}
