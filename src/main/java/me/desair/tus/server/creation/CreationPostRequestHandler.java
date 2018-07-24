package me.desair.tus.server.creation;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Server MUST acknowledge a successful upload creation with the 201 Created status.
 * The Server MUST set the Location header to the URL of the created resource. This URL MAY be absolute or relative.
 */
public class CreationPostRequestHandler extends AbstractRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CreationPostRequestHandler.class);

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final TusServletRequest servletRequest,
                        final TusServletResponse servletResponse, final UploadStorageService uploadStorageService,
                        final String ownerKey) throws IOException {

        UploadInfo info = buildUploadInfo(servletRequest);
        info = uploadStorageService.create(info, ownerKey);

        //We've already validated that the current request URL matches our upload URL so we can safely use it.
        String url = servletRequest.getRequestURL().append("/").append(info.getId()).toString();
        servletResponse.setHeader(HttpHeader.LOCATION, url);
        servletResponse.setStatus(HttpServletResponse.SC_CREATED);

        log.debug("Create upload location {}", url);
    }

    private UploadInfo buildUploadInfo(final HttpServletRequest servletRequest) {
        UploadInfo info = new UploadInfo();

        Long length = Utils.getLongHeader(servletRequest, HttpHeader.UPLOAD_LENGTH);
        if (length != null) {
            info.setLength(length);
        }

        String metadata = Utils.getHeader(servletRequest, HttpHeader.UPLOAD_METADATA);
        if (StringUtils.isNotBlank(metadata)) {
            info.setEncodedMetadata(metadata);
        }

        return info;
    }
}
