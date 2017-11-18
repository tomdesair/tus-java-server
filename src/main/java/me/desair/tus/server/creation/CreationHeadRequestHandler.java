package me.desair.tus.server.creation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.TusServletResponse;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.UUID;

/** A HEAD request can be used to retrieve the metadata that was supplied at creation.
 *
 *  If an upload contains additional metadata, responses to HEAD requests MUST include the Upload-Metadata
 *  header and its value as specified by the Client during the creation.
 */
public class CreationHeadRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.HEAD.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) {
        UUID id = idFactory.readUploadId(servletRequest);
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(id);

        if(uploadInfo.hasMetadata()) {
            servletResponse.setHeader(HttpHeader.UPLOAD_METADATA, uploadInfo.getMetadata());
        }
    }
}
