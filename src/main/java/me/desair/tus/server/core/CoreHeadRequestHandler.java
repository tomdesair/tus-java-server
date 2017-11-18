package me.desair.tus.server.core;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Objects;
import java.util.UUID;

/** A HEAD request is used to determine the offset at which the upload should be continued.
 *
 * The Server MUST always include the Upload-Offset header in the response for a HEAD request,
 * even if the offset is 0, or the upload is already considered completed. If the size of the upload is known,
 * the Server MUST include the Upload-Length header in the response.
 *
 * The Server MUST prevent the client and/or proxies from caching the response by adding
 * the Cache-Control: no-store header to the response.
 */
public class CoreHeadRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.HEAD.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) {
        UUID id = idFactory.readUploadId(servletRequest);
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(id);

        if(uploadInfo.hasLength()) {
            servletResponse.setHeader(HttpHeader.UPLOAD_LENGTH, Objects.toString(uploadInfo.getLength()));
        }

        servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, Objects.toString(uploadInfo.getOffset()));
        servletResponse.setHeader(HttpHeader.CACHE_CONTROL, "no-store");

        servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
