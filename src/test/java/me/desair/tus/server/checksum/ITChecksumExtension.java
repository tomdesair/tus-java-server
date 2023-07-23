package me.desair.tus.server.checksum;

import static org.junit.Assert.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import me.desair.tus.server.AbstractTusExtensionIntegrationTest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.UploadChecksumMismatchException;
import org.junit.Before;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ITChecksumExtension extends AbstractTusExtensionIntegrationTest {

  @Before
  public void setUp() throws Exception {
    servletRequest = spy(new MockHttpServletRequest());
    servletResponse = new MockHttpServletResponse();
    tusFeature = new ChecksumExtension();
    uploadInfo = null;
  }

  @Test
  public void testOptions() throws Exception {
    setRequestHeaders();

    executeCall(HttpMethod.OPTIONS, false);

    assertResponseHeader(HttpHeader.TUS_EXTENSION, "checksum", "checksum-trailer");
    assertResponseHeader(
        HttpHeader.TUS_CHECKSUM_ALGORITHM, "md5", "sha1", "sha256", "sha384", "sha512");
  }

  @Test(expected = ChecksumAlgorithmNotSupportedException.class)
  public void testInvalidAlgorithm() throws Exception {
    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "test 1234567890");
    servletRequest.setContent("Test content".getBytes());

    executeCall(HttpMethod.PATCH, false);
  }

  @Test
  public void testValidChecksumTrailerHeader() throws Exception {
    String content =
        "8\r\n"
            + "Mozilla \r\n"
            + "A\r\n"
            + "Developer \r\n"
            + "7\r\n"
            + "Network\r\n"
            + "0\r\n"
            + "Upload-Checksum: sha1 zYR9iS5Rya+WoH1fEyfKqqdPWWE=\r\n"
            + "\r\n";

    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(content.getBytes());

    try {
      executeCall(HttpMethod.PATCH, true);
    } catch (Exception ex) {
      fail();
    }
  }

  @Test
  public void testValidChecksumNormalHeader() throws Exception {
    String content = "Mozilla Developer Network";

    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 zYR9iS5Rya+WoH1fEyfKqqdPWWE=");
    servletRequest.setContent(content.getBytes());

    executeCall(HttpMethod.PATCH, true);

    verify(servletRequest, atLeastOnce()).getHeader(HttpHeader.UPLOAD_CHECKSUM);
  }

  @Test(expected = UploadChecksumMismatchException.class)
  public void testInvalidChecksumTrailerHeader() throws Exception {
    String content =
        "8\r\n"
            + "Mozilla \r\n"
            + "A\r\n"
            + "Developer \r\n"
            + "7\r\n"
            + "Network\r\n"
            + "0\r\n"
            + "Upload-Checksum: sha1 zYR9iS5Rya+WoH1fEyfKqqdPWW=\r\n"
            + "\r\n";

    servletRequest.addHeader(HttpHeader.TRANSFER_ENCODING, "chunked");
    servletRequest.setContent(content.getBytes());

    executeCall(HttpMethod.PATCH, true);
  }

  @Test(expected = UploadChecksumMismatchException.class)
  public void testInvalidChecksumNormalHeader() throws Exception {
    String content = "Mozilla Developer Network";

    servletRequest.addHeader(HttpHeader.UPLOAD_CHECKSUM, "sha1 zYR9iS5Rya+WoH1fEyfKqqdPWW=");
    servletRequest.setContent(content.getBytes());

    executeCall(HttpMethod.PATCH, true);
  }

  @Test
  public void testNoChecksum() throws Exception {
    String content = "Mozilla Developer Network";

    servletRequest.setContent(content.getBytes());

    try {
      executeCall(HttpMethod.PATCH, true);
    } catch (Exception ex) {
      fail();
    }
  }
}
