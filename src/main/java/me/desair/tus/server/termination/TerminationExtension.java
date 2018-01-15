package me.desair.tus.server.termination;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.util.AbstractTusFeature;

/**
 * This extension defines a way for the Client to terminate completed and unfinished
 * uploads allowing the Server to free up used resources.
 *
 * If this extension is supported by the Server, it MUST be announced by adding "termination"
 * to the Tus-Extension header.
 */
public class TerminationExtension extends AbstractTusFeature {

    @Override
    public String getName() {
        return "termination";
    }

    @Override
    public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
        return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.DELETE);
    }

    @Override
    protected void initValidators(final List<RequestValidator> requestValidators) {
        //All validation is all read done by the Core protocol
    }

    @Override
    protected void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new TerminationDeleteRequestHandler());
        requestHandlers.add(new TerminationOptionsRequestHandler());
    }
}
