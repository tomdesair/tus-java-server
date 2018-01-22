package me.desair.tus.server.expiration;

import java.io.IOException;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * If the expiration is known at the creation, the Upload-Expires header MUST be included in the response to
 * the initial POST request. Its value MAY change over time. The value of the Upload-Expires header MUST be in
 * RFC 7231 (https://tools.ietf.org/html/rfc7231#section-7.1.1.1) datetime format.
 */
public class ExpirationPostRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

    @Override
    public void process(HttpMethod method, TusServletRequest servletRequest, TusServletResponse servletResponse,
                        UploadStorageService uploadStorageService, String ownerKey) throws IOException, TusException {

        Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(servletResponse.getHeader(HttpHeader.LOCATION), ownerKey);

        if(expirationPeriod != null && expirationPeriod > 0 && uploadInfo != null) {

            uploadInfo.updateExpiration(expirationPeriod);
            uploadStorageService.update(uploadInfo);

            servletResponse.setDateHeader(HttpHeader.UPLOAD_EXPIRES, uploadInfo.getExpirationTimestamp());
        }
    }

}
