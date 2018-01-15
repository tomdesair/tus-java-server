package me.desair.tus.server;

import java.io.IOException;
import java.util.Collection;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Interface that represents a feature in the tus protocol
 */
public interface TusFeature {

    String getName();

    void validate(final HttpMethod method, final HttpServletRequest servletRequest, final UploadStorageService uploadStorageService, final String ownerKey) throws TusException, IOException;

    void process(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse, final UploadStorageService uploadStorageService, final String ownerKey) throws IOException, TusException;

    Collection<HttpMethod> getMinimalSupportedHttpMethods();

}
