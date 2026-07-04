package me.desair.tus.server.rufh.handler;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.HttpProblemDetails;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for HTTP OPTIONS feature discovery requests.
 *
 * <p>Sets Accept-Patch, Upload-Draft, and Upload-Limit headers on the response.
 *
 * <p>Reference: Section 4.1.4 (Limits) & Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-11.
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

    servletResponse.setHeader(
        HttpHeader.ACCEPT_PATCH,
        HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD + ", application/offset+octet-stream");
    servletResponse.setHeader(HttpHeader.UPLOAD_DRAFT, "11");

    addUploadLimitHeader(servletResponse, uploadStorageService);
    servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    return null;
  }

  private void addUploadLimitHeader(
      TusServletResponse response, UploadStorageService uploadStorageService) {
    if (uploadStorageService == null) {
      return;
    }
    Map<String, Object> limits = new LinkedHashMap<>();
    long maxSize = uploadStorageService.getMaxUploadSize();
    if (maxSize > 0) {
      limits.put("max-size", maxSize);
    }
    Long maxAppendSize = uploadStorageService.getMaxAppendSize();
    if (maxAppendSize != null && maxAppendSize > 0) {
      limits.put("max-append-size", maxAppendSize);
    }
    if (!limits.isEmpty()) {
      response.setHeader(HttpHeader.UPLOAD_LIMIT, StructuredHeaderUtil.formatDictionary(limits));
    }
  }
}
