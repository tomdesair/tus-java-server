package me.desair.tus.server.creation;

import me.desair.tus.server.*;
import me.desair.tus.server.creation.validation.PostURIValidator;
import me.desair.tus.server.creation.validation.PostEmptyRequestValidator;
import me.desair.tus.server.creation.validation.UploadDeferLengthValidator;
import me.desair.tus.server.creation.validation.UploadLengthValidator;
import me.desair.tus.server.util.AbstractTusFeature;

import java.util.List;

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
