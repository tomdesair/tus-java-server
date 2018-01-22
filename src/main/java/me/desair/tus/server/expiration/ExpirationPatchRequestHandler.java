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
 * The Upload-Expires response header indicates the time after which the unfinished upload expires.
 * This header MUST be included in every PATCH response if the upload is going to expire. Its value MAY change over time.
 */
public class ExpirationPatchRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

    @Override
    public void process(HttpMethod method, TusServletRequest servletRequest, TusServletResponse servletResponse,
                        UploadStorageService uploadStorageService, String ownerKey) throws IOException, TusException {

        Long expirationPeriod = uploadStorageService.getUploadExpirationPeriod();
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);

        //Only set expiration header for uploads that are unfinished
        if(expirationPeriod != null && expirationPeriod > 0
                && uploadInfo != null && uploadInfo.isUploadInProgress()) {

            uploadInfo.updateExpiration(expirationPeriod);
            uploadStorageService.update(uploadInfo);

            servletResponse.setDateHeader(HttpHeader.UPLOAD_EXPIRES, uploadInfo.getExpirationTimestamp());
        }
    }
}
