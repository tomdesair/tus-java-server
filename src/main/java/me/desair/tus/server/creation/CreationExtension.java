package me.desair.tus.server.creation;

import java.util.List;

import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.creation.validation.PostEmptyRequestValidator;
import me.desair.tus.server.creation.validation.PostURIValidator;
import me.desair.tus.server.creation.validation.UploadDeferLengthValidator;
import me.desair.tus.server.creation.validation.UploadLengthValidator;
import me.desair.tus.server.util.AbstractTusFeature;

/**
 * The Client and the Server SHOULD implement the upload creation extension. If the Server supports this extension.
 */
public class CreationExtension extends AbstractTusFeature {

    @Override
    public String getName() {
        return "creation";
    }

    @Override
    protected void initValidators(final List<RequestValidator> requestValidators) {
        requestValidators.add(new PostURIValidator());
        requestValidators.add(new PostEmptyRequestValidator());
        requestValidators.add(new UploadDeferLengthValidator());
        requestValidators.add(new UploadLengthValidator());
    }

    @Override
    protected void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new CreationHeadRequestHandler());
        requestHandlers.add(new CreationPatchRequestHandler());
        requestHandlers.add(new CreationPostRequestHandler());
        requestHandlers.add(new CreationOptionsRequestHandler());
    }
}
