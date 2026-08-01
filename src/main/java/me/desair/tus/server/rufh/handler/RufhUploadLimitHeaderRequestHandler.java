package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

/**
 * Request handler for the RUFH protocol that populates the {@code Upload-Limit} response header
 * field in accordance with Section 4.1.4 & Section 4.6.2 of draft-ietf-httpbis-resumable-upload-12
 * for all applicable HTTP methods (OPTIONS, HEAD, GET, POST, PATCH).
 */
public class RufhUploadLimitHeaderRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return !HttpMethod.DELETE.equals(method);
  }

  @Override
  public boolean supports(HttpMethod method, ProtocolVersion version) {
    return (version == ProtocolVersion.RUFH || HttpMethod.OPTIONS.equals(method))
        && supports(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    if (servletResponse == null || uploadStorageService == null) {
      return;
    }

    String uploadUri = servletResponse.getHeader(HttpHeader.LOCATION);
    if (StringUtils.isBlank(uploadUri) && servletRequest != null) {
      uploadUri = servletRequest.getRequestURI();
    }

    UploadInfo uploadInfo = null;
    try {
      uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
    } catch (Exception e) {
      uploadInfo = null;
    }

    addUploadLimitHeader(servletResponse, uploadStorageService, uploadInfo);
  }

  @Override
  public boolean isErrorHandler() {
    return true;
  }

  private void addUploadLimitHeader(
      TusServletResponse response, UploadStorageService storageService, UploadInfo uploadInfo) {
    Map<String, Object> limits = new LinkedHashMap<>();

    long maxSize = storageService.getMaxUploadSize();
    if (maxSize > 0) {
      limits.put("max-size", maxSize);
    }

    Long minSize = storageService.getMinSize();
    if (minSize != null && minSize > 0) {
      limits.put("min-size", minSize);
    }

    Long maxAppendSize = storageService.getMaxAppendSize();
    if (maxAppendSize != null && maxAppendSize > 0) {
      limits.put("max-append-size", maxAppendSize);
    }

    Long minAppendSize = storageService.getMinAppendSize();
    if (minAppendSize != null && minAppendSize > 0) {
      limits.put("min-append-size", minAppendSize);
    }

    Long maxAgeSeconds = calculateMaxAgeSeconds(storageService, uploadInfo);
    if (maxAgeSeconds != null && maxAgeSeconds >= 0) {
      limits.put("max-age", maxAgeSeconds);
    }

    // Section 4.1.4 / 4.6.2: If the server does not apply any limits, it MUST use min-size=0
    if (limits.isEmpty()) {
      limits.put("min-size", 0L);
    }

    response.setHeader(HttpHeader.UPLOAD_LIMIT, StructuredHeaderUtil.formatDictionary(limits));
  }

  private Long calculateMaxAgeSeconds(UploadStorageService storageService, UploadInfo uploadInfo) {
    if (uploadInfo != null && uploadInfo.getExpirationTimestamp() != null) {
      long remainingMs = uploadInfo.getExpirationTimestamp() - System.currentTimeMillis();
      return Math.max(0L, remainingMs / 1000L);
    } else if (storageService != null
        && storageService.getUploadExpirationPeriod() != null
        && storageService.getUploadExpirationPeriod() > 0) {
      return Math.max(0L, storageService.getUploadExpirationPeriod() / 1000L);
    }
    return null;
  }
}
