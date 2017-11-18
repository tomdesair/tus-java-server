package me.desair.tus.server.core;

import me.desair.tus.server.*;
import me.desair.tus.server.core.validation.*;

import java.util.List;

/**
 * The core protocol describes how to resume an interrupted upload.
 * It assumes that you already have a URL for the upload, usually created via the Creation extension.
 * All Clients and Servers MUST implement the core protocol.
 */
public class CoreProtocol extends AbstractTusFeature {

    @Override
    public String getName() {
        return "core";
    }

    @Override
    protected void initValidators(final List<RequestValidator> validators) {
        validators.add(new HttpMethodValidator());
        validators.add(new TusResumableValidator());
        validators.add(new IdExistsValidator());
        validators.add(new ContentTypeValidator());
        validators.add(new UploadOffsetValidator());
        validators.add(new ContentLengthValidator());
    }

    @Override
    protected void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new CoreTusResumableHandler());
        requestHandlers.add(new CoreHeadRequestHandler());
        requestHandlers.add(new CoreOptionsRequestHandler());
        requestHandlers.add(new CorePatchRequestHandler());
    }
}
