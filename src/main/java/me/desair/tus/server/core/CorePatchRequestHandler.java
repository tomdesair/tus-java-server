package me.desair.tus.server.core;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.Utils;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The Server SHOULD accept PATCH requests against any upload URL and apply the bytes contained in the message at
 * the given offset specified by the Upload-Offset header.
 *
 * The Server MUST acknowledge successful PATCH requests with the 204 No Content status. It MUST include the
 * Upload-Offset header containing the new offset. The new offset MUST be the sum of the offset before the PATCH
 * request and the number of bytes received and processed or stored during the current PATCH request.
 */
public class CorePatchRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final HttpServletResponse servletResponse,
                        final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws IOException {

        UUID id = idFactory.readUploadId(servletRequest);
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(id);

        if(uploadInfo.isUploadInProgress()) {
            Long offset = Utils.getLongHeader(servletRequest, HttpHeader.UPLOAD_OFFSET);

            uploadInfo = uploadStorageService.append(id, offset, servletRequest.getInputStream());
        }

        servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, Objects.toString(uploadInfo.getOffset()));
        servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
