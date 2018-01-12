package me.desair.tus.server.download;

import java.util.List;

import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.termination.TerminationDeleteRequestHandler;
import me.desair.tus.server.util.AbstractTusFeature;

/**
 * Some Tus clients also send GET request to retrieve the uploaded content. We consider this
 * as an unofficial extension.
 */
public class DownloadExtension extends AbstractTusFeature {

    @Override
    public String getName() {
        return "download";
    }

    @Override
    protected void initValidators(final List<RequestValidator> requestValidators) {
        //All validation is all read done by the Core protocol
    }

    @Override
    protected void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new DownloadGetRequestHandler());
        requestHandlers.add(new DownloadOptionsRequestHandler());
    }
}
