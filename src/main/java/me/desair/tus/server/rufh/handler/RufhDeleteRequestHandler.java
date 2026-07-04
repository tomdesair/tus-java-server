package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.HttpProblemDetails;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for upload cancellation requests via HTTP DELETE.
 *
 * <p>Terminates the upload resource on storage and responds with HTTP 204 No Content.
 *
 * <p>Reference: Section 4.5 (Upload Cancellation) & Section 4.5.2 (Server Behavior) of
 * draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhDeleteRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.DELETE.equals(method);
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

    String requestUri = servletRequest.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);

    if (uploadInfo != null) {
      uploadStorageService.terminateUpload(uploadInfo);
    }
    servletResponse.setStatus(204);
    return null;
  }
}
