package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler responsible for setting common response headers that must be present in all
 * Resumable Uploads for HTTP (RUFH) responses.
 *
 * <p>Specifically, sets the {@code Upload-Draft} header to {@code 11}.
 *
 * <p>Reference: Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhResponseHeadersHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return true;
  }

  @Override
  public boolean isErrorHandler() {
    return true;
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

    servletResponse.setHeader(HttpHeader.UPLOAD_DRAFT, "11");
    return null;
  }
}
