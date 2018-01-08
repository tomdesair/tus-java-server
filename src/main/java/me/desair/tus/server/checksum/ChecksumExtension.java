package me.desair.tus.server.checksum;

import java.util.List;

import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.checksum.validation.ChecksumAlgorithmValidator;
import me.desair.tus.server.util.AbstractTusFeature;

/**
 * The Client and the Server MAY implement and use this extension to verify data integrity of each PATCH request.
 * If supported, the Server MUST add checksum to the Tus-Extension header.
 */
public class ChecksumExtension extends AbstractTusFeature {

    @Override
    public String getName() {
        return "checksum";
    }

    @Override
    protected void initValidators(final List<RequestValidator> requestValidators) {
        requestValidators.add(new ChecksumAlgorithmValidator());
    }

    @Override
    protected void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new ChecksumOptionsRequestHandler());
        requestHandlers.add(new ChecksumPatchRequestHandler());
    }
}
