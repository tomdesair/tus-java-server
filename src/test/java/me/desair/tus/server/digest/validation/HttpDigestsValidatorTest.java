package me.desair.tus.server.digest.validation;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class HttpDigestsValidatorTest {

  private HttpDigestsValidator validator;
  private UploadStorageService uploadStorageService;

  @Before
  public void setUp() {
    validator = new HttpDigestsValidator();
    uploadStorageService = mock(UploadStorageService.class);
  }

  @Test
  public void testSupports() {
    assertThat(validator.supports(HttpMethod.POST), is(true));
    assertThat(validator.supports(HttpMethod.PUT), is(true));
    assertThat(validator.supports(HttpMethod.PATCH), is(true));
    assertThat(validator.supports(HttpMethod.GET), is(false));
  }

  @Test
  public void testValidateNoHeaders() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    validator.validate(HttpMethod.POST, request, uploadStorageService, "owner");
  }

  @Test
  public void testValidateValidContentDigest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        HttpHeader.CONTENT_DIGEST, "sha-256=:ungWvEM12g1ENZE8BHksJU25yTY7iWi5KyMT+h0B+Ys=:");
    validator.validate(HttpMethod.POST, request, uploadStorageService, "owner");
  }

  @Test
  public void testValidateUnsupportedContentDigestAlgorithm() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeader.CONTENT_DIGEST, "unknown=:abc=:");

    assertThrows(
        ChecksumAlgorithmNotSupportedException.class,
        () -> validator.validate(HttpMethod.POST, request, uploadStorageService, "owner"));
  }

  @Test
  public void testValidateInvalidContentDigestHeaderFormat() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeader.CONTENT_DIGEST, "invalid-format-here");

    assertThrows(
        TusException.class,
        () -> validator.validate(HttpMethod.POST, request, uploadStorageService, "owner"));
  }

  @Test
  public void testValidateValidReprDigest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(
        HttpHeader.REPR_DIGEST, "sha-256=:ungWvEM12g1ENZE8BHksJU25yTY7iWi5KyMT+h0B+Ys=:");
    validator.validate(HttpMethod.POST, request, uploadStorageService, "owner");
  }

  @Test
  public void testValidateUnsupportedReprDigestAlgorithm() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeader.REPR_DIGEST, "unknown=:abc=:");

    assertThrows(
        ChecksumAlgorithmNotSupportedException.class,
        () -> validator.validate(HttpMethod.POST, request, uploadStorageService, "owner"));
  }

  @Test
  public void testValidateValidWantReprDigest() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeader.WANT_REPR_DIGEST, "sha-256, sha-512");
    validator.validate(HttpMethod.POST, request, uploadStorageService, "owner");
  }

  @Test
  public void testValidateInvalidWantReprDigestFormat() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(HttpHeader.WANT_REPR_DIGEST, "invalid-format-here,;");

    assertThrows(
        TusException.class,
        () -> validator.validate(HttpMethod.POST, request, uploadStorageService, "owner"));
  }
}
