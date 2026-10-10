package me.desair.tus.server.termination;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * When receiving a DELETE request for an existing upload the Server SHOULD free associated
 * resources and MUST respond with the 204 No Content status confirming that the upload was
 * terminated. For all future requests to this URL the Server SHOULD respond with the 404 Not Found
 * or 410 Gone status.
 */
public class TerminationDeleteRequestHandler extends AbstractRequestHandler {

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

    UploadInfo uploadInfo =
        uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);

    if (uploadInfo != null) {
      uploadStorageService.terminateUpload(uploadInfo);
      if (uploadLockingService != null) {
        uploadLockingService.cleanupLock(uploadInfo.getId());
      }
    }

    servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    return null;
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {
    process(method, servletRequest, servletResponse, uploadStorageService, null, ownerKey, null);
  }
}
