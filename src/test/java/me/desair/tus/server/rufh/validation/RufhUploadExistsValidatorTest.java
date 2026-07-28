package me.desair.tus.server.rufh.validation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhUploadExistsValidatorTest {

  private RufhUploadExistsValidator validator;
  private MockHttpServletRequest request;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    validator = new RufhUploadExistsValidator();
    request = new MockHttpServletRequest();
  }

  @Test
  public void testSupports() {
    assertTrue(validator.supports(HttpMethod.HEAD));
    assertTrue(validator.supports(HttpMethod.DELETE));
    assertFalse(validator.supports(HttpMethod.POST));
  }

  /**
   * Section 6.1 (Status Request) & Section 7 (Upload Cancellation): "If the upload resource does
   * not exist, the server MUST reject the request with a 404 (Not Found) status code."
   */
  @Test(expected = TusException.class)
  public void testValidateUploadDoesNotExist() throws Exception {
    request.setRequestURI("/files/non-existent-id");
    when(storageService.getUploadInfo("/files/non-existent-id", "owner")).thenReturn(null);

    validator.validate(HttpMethod.HEAD, request, storageService, "owner");
  }

  @Test
  public void testValidateUploadExists() throws Exception {
    request.setRequestURI("/files/exists-id");
    when(storageService.getUploadInfo("/files/exists-id", "owner")).thenReturn(new UploadInfo());

    validator.validate(HttpMethod.HEAD, request, storageService, "owner");
  }
}
