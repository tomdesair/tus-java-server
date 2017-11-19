package me.desair.tus.server;

import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

public interface RequestHandler {

    boolean supports(final HttpMethod method);

    void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService) throws IOException;
}
