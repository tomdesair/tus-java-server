package me.desair.tus.server.creation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/** A HEAD request can be used to retrieve the metadata that was supplied at creation.
 *
 *  If an upload contains additional metadata, responses to HEAD requests MUST include the Upload-Metadata
 *  header and its value as specified by the Client during the creation.
 *
 *  As long as the length of the upload is not known, the Server MUST set Upload-Defer-Length: 1 in
 *  all responses to HEAD requests.
 */
public class CreationHeadRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.HEAD.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService) throws IOException {
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(servletRequest.getRequestURI());

        if(uploadInfo.hasMetadata()) {
            servletResponse.setHeader(HttpHeader.UPLOAD_METADATA, uploadInfo.getEncodedMetadata());
        }

        if(!uploadInfo.hasLength()) {
            servletResponse.setHeader(HttpHeader.UPLOAD_DEFER_LENGTH, "1");
        }
    }
}
