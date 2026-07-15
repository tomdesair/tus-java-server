package me.desair.tus.server.rufh.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class RufhSafePathValidatorTest {

  private RufhSafePathValidator validator;
  private MockHttpServletRequest request;

  @Before
  public void setUp() {
    validator = new RufhSafePathValidator();
    request = new MockHttpServletRequest();
  }

  @Test
  public void testSupports() {
    assertTrue(validator.supports(HttpMethod.POST));
    assertTrue(validator.supports(HttpMethod.GET));
    assertFalse(validator.supports(null));
  }

  /**
   * Section 10 (Security Considerations): "Servers MUST prevent path traversal attacks in upload
   * URIs."
   */
  @Test(expected = TusException.class)
  public void testValidatePathTraversalDotDot() throws Exception {
    request.setRequestURI("/files/../etc/passwd");
    validator.validate(HttpMethod.POST, request, null, null);
  }

  /**
   * Section 10 (Security Considerations): "Servers MUST reject null bytes in upload request paths."
   */
  @Test(expected = TusException.class)
  public void testValidatePathTraversalNullByte() throws Exception {
    request.setRequestURI("/files/test\0file");
    validator.validate(HttpMethod.POST, request, null, null);
  }

  @Test
  public void testValidateSafePath() throws Exception {
    request.setRequestURI("/files/valid-upload-id");
    validator.validate(HttpMethod.POST, request, null, null);
  }

  @Test
  public void testValidateNullPath() throws Exception {
    request.setRequestURI(null);
    validator.validate(HttpMethod.POST, request, null, null);
  }
}
