package me.desair.tus.server.rufh.handler;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for HTTP OPTIONS feature discovery requests.
 *
 * <p>Reference: Section 4.1.4 (Limits) & Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-12.
 */
public class RufhOptionsRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.OPTIONS.equals(method);
  }

  @Override
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    return null;
  }
}
