package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.InconsistentUploadLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyCompletedException;
import me.desair.tus.server.exception.UploadDigestMismatchException;
import me.desair.tus.server.exception.UploadOffsetMismatchException;
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
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    if (exception instanceof UploadOffsetMismatchException) {
      // Section 7.1: Mismatching Offset
      UploadInfo uploadInfo =
          uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
      long expectedOffset =
          (uploadInfo != null && uploadInfo.getOffset() != null) ? uploadInfo.getOffset() : 0L;
      Long providedOffset =
          StructuredHeaderUtil.parseInteger(servletRequest.getHeader(HttpHeader.UPLOAD_OFFSET));
      long provided = providedOffset != null ? providedOffset : 0L;

      return HttpProblemDetails.forOffsetMismatch(expectedOffset, provided);

    } else if (exception instanceof UploadAlreadyCompletedException) {
      // Section 7.2: Completed Upload
      return HttpProblemDetails.forCompletedUpload(400);

    } else if (exception instanceof InconsistentUploadLengthException) {
      // Section 7.3: Inconsistent Length
      return HttpProblemDetails.forInconsistentLength();
    } else if (exception instanceof UploadDigestMismatchException) {
      // RFC 9530 Mismatched Digest Values
      return HttpProblemDetails.forDigestMismatch();
    }
    return null;
  }
}
