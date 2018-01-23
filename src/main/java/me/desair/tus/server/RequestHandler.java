package me.desair.tus.server;

import java.io.IOException;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

public interface RequestHandler {

    boolean supports(final HttpMethod method);

    void process(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService, final String ownerKey) throws IOException, TusException;

    boolean isErrorHandler();

}
