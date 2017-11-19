package me.desair.tus.server;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletResponse;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * Interface that represents a feature in the tus protocol
 */
public interface TusFeature {

    String getName();

    void validate(final HttpMethod method, final HttpServletRequest servletRequest, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException, IOException;

    void process(final HttpMethod method, final HttpServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws IOException;

}
