package me.desair.tus.server.creation;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Upload-Defer-Length: 1 if upload size is not known at the time. Once it is known the Client MUST set
 * the Upload-Length header in the next PATCH request. Once set the length MUST NOT be changed.
 */
public class CreationPatchRequestHandler extends AbstractRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CreationPatchRequestHandler.class);

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

    @Override
    public void process(HttpMethod method, TusServletRequest servletRequest,
                        TusServletResponse servletResponse, UploadStorageService uploadStorageService,
                        String ownerKey) throws IOException {

        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);

        if (uploadInfo != null && !uploadInfo.hasLength()) {
            Long uploadLength = Utils.getLongHeader(servletRequest, HttpHeader.UPLOAD_LENGTH);
            if (uploadLength != null) {
                uploadInfo.setLength(uploadLength);
                try {
                    uploadStorageService.update(uploadInfo);
                } catch (UploadNotFoundException e) {
                    log.error("The patch request handler could not find the upload for URL "
                            + servletRequest.getRequestURI()
                            + ". This means something is really wrong the request validators!", e);
                    servletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                }
            }
        }
    }
}
