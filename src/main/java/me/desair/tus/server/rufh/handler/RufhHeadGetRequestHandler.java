package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;

/**
 * Request handler for HTTP HEAD and GET offset retrieval requests against upload resources.
 *
 * <p>Sets Upload-Offset, Upload-Complete, Upload-Length, Upload-Limit, Upload-Draft, and
 * Cache-Control headers on the response.
 *
 * <p>Reference: Section 4.3 (Offset Retrieval) & Section 4.3.2 (Server Behavior) of
 * draft-ietf-httpbis-resumable-upload-12:
 *
 * <ul>
 *   <li>"A successful response to a HEAD or GET request against an upload resource MUST include the
 *       offset in the Upload-Offset header field..."
 *   <li>"MUST include the Upload-Complete header field..."
 *   <li>"MUST indicate the limits in the Upload-Limit header field..."
 *   <li>"SHOULD include the Cache-Control header field with the value no-store..."
 *   <li>"A client does not require response content for an offset retrieval request in order to
 *       successfully resume an upload. Therefore, serving response content for a GET request is
 *       unexpected. Its meaning is not defined by this protocol."
 * </ul>
 */
public class RufhHeadGetRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.HEAD.equals(method) || HttpMethod.GET.equals(method);
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
    if (!Utils.isCreationEndpoint(servletRequest, uploadStorageService)) {
      if (uploadInfo == null || uploadInfo.isExpired()) {
        throw new UploadNotFoundException("Upload resource not found");
      }
    } else if (uploadInfo == null || uploadInfo.isExpired()) {
      return null;
    }

    servletResponse.setStatus(204);
    servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    servletResponse.setHeader(
        HttpHeader.UPLOAD_COMPLETE,
        StructuredHeaderUtil.formatBoolean(!uploadInfo.isUploadInProgress()));

    if (uploadInfo.hasLength()) {
      servletResponse.setHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(uploadInfo.getLength()));
    }

    servletResponse.setHeader(HttpHeader.CACHE_CONTROL, "no-store");
    return null;
  }
}
