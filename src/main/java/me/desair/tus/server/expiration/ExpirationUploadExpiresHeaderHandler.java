package me.desair.tus.server.expiration;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

/**
 * Request handler to set the Tus 1.0.0 specific {@code Upload-Expires} response header on POST and
 * PATCH responses when an expiration period is configured.
 */
public class ExpirationUploadExpiresHeaderHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method) || HttpMethod.POST.equals(method);
  }

  @Override
  public boolean supports(HttpMethod method, ProtocolVersion version) {
    return (HttpMethod.PATCH.equals(method) || HttpMethod.POST.equals(method))
        && version == ProtocolVersion.TUS_1_0_0;
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    String uploadUri = servletResponse.getHeader(HttpHeader.LOCATION);
    if (StringUtils.isBlank(uploadUri)) {
      uploadUri = servletRequest.getRequestURI();
    }

    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);

    if (uploadInfo != null && uploadInfo.getExpirationTimestamp() != null) {
      servletResponse.setDateHeader(HttpHeader.UPLOAD_EXPIRES, uploadInfo.getExpirationTimestamp());
    }
  }

  @Override
  public boolean isErrorHandler() {
    return true;
  }
}
