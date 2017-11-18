package me.desair.tus.server;

import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public interface RequestHandler {

    boolean supports(final HttpMethod method);

    void process(final HttpMethod method, final HttpServletRequest servletRequest, final HttpServletResponse servletResponse, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws IOException;
}
