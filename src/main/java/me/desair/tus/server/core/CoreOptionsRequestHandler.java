package me.desair.tus.server.core;

import me.desair.tus.server.*;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;

import javax.servlet.http.HttpServletRequest;
import java.util.Objects;

/**
 * An OPTIONS request MAY be used to gather information about the Server’s current configuration. A successful
 * response indicated by the 204 No Content or 200 OK status MUST contain the Tus-Version header. It MAY include
 * the Tus-Extension and Tus-Max-Size headers.
 */
public class CoreOptionsRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.OPTIONS.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService) {
        if(uploadStorageService.getMaxSizeInBytes() > 0) {
            servletResponse.setHeader(HttpHeader.TUS_MAX_SIZE, Objects.toString(uploadStorageService.getMaxSizeInBytes()));
        }

        servletResponse.setHeader(HttpHeader.TUS_VERSION, TusFileUploadReceivingService.TUS_API_VERSION);
    }
}
