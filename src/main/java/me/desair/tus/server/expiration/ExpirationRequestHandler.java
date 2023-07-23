package me.desair.tus.server.expiration;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

/**
 * The Upload-Expires response header indicates the time after which the unfinished upload expires.
 * This header MUST be included in every PATCH response if the upload is going to expire. Its value
 * MAY change over time. If the expiration is known at the creation, the Upload-Expires header MUST
 * be included in the response to the initial POST request. Its value MAY change over time. The
 * value of the Upload-Expires header MUST be in RFC 7231
 * (https://tools.ietf.org/html/rfc7231#section-7.1.1.1) datetime format.
 */
public class ExpirationRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method) || HttpMethod.POST.equals(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    // For post requests, the upload URI is part of the response
    String uploadUri = servletResponse.getHeader(HttpHeader.LOCATION);
    if (StringUtils.isBlank(uploadUri)) {
      // For patch request, our upload URI is the URI of the request
      uploadUri = servletRequest.getRequestURI();
    }

    Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);

    // The Upload-Expires response header MUST be included in every PATCH response if the upload
    // is
    // going to expire.
    // If the expiration is known at the creation, the Upload-Expires header MUST be included in
    // the
    // response to
    // the initial POST request. Its value MAY change over time.

    if (expirationPeriod != null && expirationPeriod > 0 && uploadInfo != null) {

      uploadInfo.updateExpiration(expirationPeriod);
      uploadStorageService.update(uploadInfo);

      servletResponse.setDateHeader(HttpHeader.UPLOAD_EXPIRES, uploadInfo.getExpirationTimestamp());
    }
  }

  @Override
  public boolean isErrorHandler() {
    return true;
  }
}
