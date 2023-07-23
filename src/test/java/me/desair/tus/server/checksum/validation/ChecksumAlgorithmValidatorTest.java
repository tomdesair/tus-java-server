package me.desair.tus.server.checksum.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class ChecksumAlgorithmValidatorTest {

  private ChecksumAlgorithmValidator validator;

  private MockHttpServletRequest servletRequest;

  @Mock private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    servletRequest = spy(new MockHttpServletRequest());
    validator = new ChecksumAlgorithmValidator();
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
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 1234567890");

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);

    verify(servletRequest, times(1)).getHeader(HttpHeader.UPLOAD_CHECKSUM);
  }

  @Test
  public void testNoHeader() throws Exception {
    // servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, null);

    try {
      validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
    } catch (Exception ex) {
      fail();
    }
  }

  @Test(expected = ChecksumAlgorithmNotSupportedException.class)
  public void testInvalidHeader() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "test 1234567890");

    validator.validate(HttpMethod.PATCH, servletRequest, uploadStorageService, null);
  }
}
