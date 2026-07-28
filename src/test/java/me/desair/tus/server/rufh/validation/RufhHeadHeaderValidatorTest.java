package me.desair.tus.server.rufh.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class RufhHeadHeaderValidatorTest {

  private RufhHeadHeaderValidator validator;
  private MockHttpServletRequest request;

  @Before
  public void setUp() {
    validator = new RufhHeadHeaderValidator();
    request = new MockHttpServletRequest();
  }

  @Test
  public void testSupports() {
    assertTrue(validator.supports(HttpMethod.HEAD));
    assertFalse(validator.supports(HttpMethod.POST));
  }

  /**
   * Section 6.1 (Status Request): "The request MUST NOT contain Upload-Offset or Upload-Complete
   * header fields."
   */
  @Test(expected = TusException.class)
  public void testValidateWithUploadOffsetHeader() throws Exception {
    request.addHeader(HttpHeader.UPLOAD_OFFSET, "100");
    validator.validate(HttpMethod.HEAD, request, null, null);
  }

  /**
   * Section 6.1 (Status Request): "The request MUST NOT contain Upload-Offset or Upload-Complete
   * header fields."
   */
  @Test(expected = TusException.class)
  public void testValidateWithUploadCompleteHeader() throws Exception {
    request.addHeader(HttpHeader.UPLOAD_COMPLETE, "?0");
    validator.validate(HttpMethod.HEAD, request, null, null);
  }

  @Test
  public void testValidateCleanHeadRequest() throws Exception {
    validator.validate(HttpMethod.HEAD, request, null, null);
  }
}
