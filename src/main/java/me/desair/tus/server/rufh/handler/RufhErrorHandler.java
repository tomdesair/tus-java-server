package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.HttpProblemDetails;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Error request handler that processes validation and processing failures for RUFH requests.
 *
 * <p>Formats offset mismatch errors (HTTP 409 Conflict), completed upload errors (HTTP 400 Bad
 * Request), and inconsistent upload length errors (HTTP 400 Bad Request) as RFC 7807 Problem
 * Details JSON.
 *
 * <p>Reference: Section 7 (Problem Types) of draft-ietf-httpbis-resumable-upload-11:
 *
 * <ul>
 *   <li>Section 7.1: Mismatching Offset (409 Conflict)
 *   <li>Section 7.2: Completed Upload (400 Bad Request)
 *   <li>Section 7.3: Inconsistent Length (400 Bad Request)
 * </ul>
 */
public class RufhErrorHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return true;
  }

  @Override
  public boolean isErrorHandler() {
    return true;
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {
    process(method, servletRequest, servletResponse, uploadStorageService, null, ownerKey);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey)
      throws IOException, TusException {

    int status = servletResponse.getStatus();
    if (status == 409) {
      // Section 7.1: Mismatching Offset
      UploadInfo uploadInfo =
          uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
      long expectedOffset =
          (uploadInfo != null && uploadInfo.getOffset() != null) ? uploadInfo.getOffset() : 0L;
      Long providedOffset =
          StructuredHeaderUtil.parseInteger(servletRequest.getHeader(HttpHeader.UPLOAD_OFFSET));
      long provided = providedOffset != null ? providedOffset : 0L;

      HttpProblemDetails.forOffsetMismatch(expectedOffset, provided).writeTo(servletResponse);

    } else if (status == 400) {
      UploadInfo uploadInfo =
          uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
      if (uploadInfo != null && !uploadInfo.isUploadInProgress()) {
        // Section 7.2: Completed Upload
        HttpProblemDetails.forCompletedUpload(400).writeTo(servletResponse);
      } else {
        // Section 7.3: Inconsistent Length
        HttpProblemDetails.forInconsistentLength().writeTo(servletResponse);
      }
    }
  }
}
