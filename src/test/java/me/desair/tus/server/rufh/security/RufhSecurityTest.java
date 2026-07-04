package me.desair.tus.server.rufh.security;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.ResumableUploadsForHttpProtocol;
import me.desair.tus.server.upload.UploadStorageService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Security unit tests verifying path traversal prevention in RUFH requests.
 *
 * <p>Reference: Section 10 (Security Considerations) of draft-ietf-httpbis-resumable-upload.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class RufhSecurityTest {

  private ResumableUploadsForHttpProtocol protocol;
  private MockHttpServletRequest request;

  @Mock private UploadStorageService storageService;

  @Before
  public void setUp() {
    protocol = new ResumableUploadsForHttpProtocol();
    request = new MockHttpServletRequest();
  }

  /**
   * Section 10 (Security Considerations): "Servers MUST prevent path traversal attacks in upload
   * URIs."
   */
  @Test(expected = TusException.class)
  public void testPathTraversalWithDotDotInUri() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files/../secret");

    protocol.validate(HttpMethod.POST, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 10 (Security Considerations): "Servers MUST reject null bytes in upload request paths."
   */
  @Test(expected = TusException.class)
  public void testPathTraversalWithNullByteInUri() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files/test\0file");

    protocol.validate(HttpMethod.POST, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 10 (Security Considerations): "HEAD requests containing path traversal sequences MUST
   * be rejected."
   */
  @Test(expected = TusException.class)
  public void testPathTraversalWithDotDotInHead() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/../../etc/passwd");

    protocol.validate(HttpMethod.HEAD, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 10 (Security Considerations): "PATCH requests containing path traversal sequences MUST
   * be rejected."
   */
  @Test(expected = TusException.class)
  public void testPathTraversalWithDotDotInPatch() throws Exception {
    request.setMethod("PATCH");
    request.setRequestURI("/files/../config");

    protocol.validate(HttpMethod.PATCH, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 13 (Security Considerations) of draft-11: "DELETE requests containing path traversal
   * sequences MUST be rejected."
   */
  @Test(expected = TusException.class)
  public void testPathTraversalWithDotDotInDelete() throws Exception {
    request.setMethod("DELETE");
    request.setRequestURI("/files/../system");

    protocol.validate(HttpMethod.DELETE, request, storageService, null, null, ProtocolVersion.RUFH);
  }

  /**
   * Section 13 (Security Considerations) of draft-11: "To reduce the risk of unauthorized access,
   * it is RECOMMENDED to generate upload resource URIs in such a way that makes it hard to be
   * guessed... The server SHOULD ensure that only authorized clients can access the upload
   * resource."
   */
  @Test(expected = TusException.class)
  public void testCrossTenantAccessPrevented() throws Exception {
    request.setMethod("HEAD");
    request.setRequestURI("/files/user1-upload");

    // storageService returns null when accessed with an unauthorized owner key ("user2")
    org.mockito.Mockito.when(storageService.getUploadInfo("/files/user1-upload", "user2"))
        .thenReturn(null);

    protocol.validate(
        HttpMethod.HEAD, request, storageService, null, "user2", ProtocolVersion.RUFH);
  }

  /**
   * Section 13 (Security Considerations) of draft-11: "Servers or intermediaries need to consider
   * that relying solely on message content limits to constrain resources allocated to uploads might
   * not be an effective strategy... Servers SHOULD provide mitigations for Slowloris attacks..."
   */
  @Test(expected = TusException.class)
  public void testMaxAppendSizeEnforcedInCreationAndAppend() throws Exception {
    request.setMethod("POST");
    request.setRequestURI("/files");
    request.setContent("Oversized payload content".getBytes());

    org.mockito.Mockito.when(storageService.getMaxAppendSize())
        .thenReturn(5L); // 5 byte max append limit

    protocol.validate(HttpMethod.POST, request, storageService, null, null, ProtocolVersion.RUFH);
  }
}
