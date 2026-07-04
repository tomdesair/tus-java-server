package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for HTTP HEAD status retrieval requests.
 *
 * <p>Sets Upload-Offset, Upload-Complete, Upload-Length, Upload-Limit, Upload-Draft, and
 * Cache-Control headers on the response.
 *
 * <p>Reference: Section 4.3 (Offset Retrieval) & Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhHeadRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.HEAD.equals(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException {
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
      throws IOException {

    String requestUri = servletRequest.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);

    servletResponse.setStatus(204);
    servletResponse.setHeader(HttpHeader.UPLOAD_DRAFT, "11");
    servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    servletResponse.setHeader(
        HttpHeader.UPLOAD_COMPLETE,
        StructuredHeaderUtil.formatBoolean(!uploadInfo.isUploadInProgress()));

    if (uploadInfo.hasLength()) {
      servletResponse.setHeader(HttpHeader.UPLOAD_LENGTH, String.valueOf(uploadInfo.getLength()));
    }

    addUploadLimitHeader(servletResponse, uploadStorageService);
    servletResponse.setHeader(HttpHeader.CACHE_CONTROL, "no-store");
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
