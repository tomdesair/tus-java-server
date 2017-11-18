package me.desair.tus.server.creation;

import me.desair.tus.server.*;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * The Server MUST acknowledge a successful upload creation with the 201 Created status.
 * The Server MUST set the Location header to the URL of the created resource. This URL MAY be absolute or relative.
 */
public class CreationPostRequestHandler implements RequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CreationPostRequestHandler.class);

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse,
                        final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws IOException {

        UUID id;
        UploadInfo info;
        do {
            id = idFactory.createId();
            info = buildUploadInfo(servletRequest, id);
        } while(!uploadStorageService.create(info));

        //We've already validated that the current request URL matches our idFactory.getUploadURI() so we can safely use it.
        String url = servletRequest.getRequestURI() + "/" + id;
        servletResponse.setHeader(HttpHeader.LOCATION, url);
        servletResponse.setStatus(HttpServletResponse.SC_CREATED);

        log.debug("Create upload location {}", url);
    }

    private UploadInfo buildUploadInfo(final HttpServletRequest servletRequest, final UUID id) {
        UploadInfo info = new UploadInfo();

        info.setId(id);

        Long length = Utils.getLongHeader(servletRequest, HttpHeader.UPLOAD_LENGTH);
        if(length != null) {
            info.setLength(length);
        }

        String metadata = Utils.getHeader(servletRequest, HttpHeader.UPLOAD_METADATA);
        if(StringUtils.isNotBlank(metadata)) {
            info.setMetadata(metadata);
        }

        return info;
    }
}
