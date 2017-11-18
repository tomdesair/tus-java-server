package me.desair.tus.server.core;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.TusFeature;
import me.desair.tus.server.core.validation.*;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.RequestValidator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * The core protocol describes how to resume an interrupted upload.
 * It assumes that you already have a URL for the upload, usually created via the Creation extension.
 * All Clients and Servers MUST implement the core protocol.
 */
public class CoreProtocol implements TusFeature {

    private final List<RequestValidator> requestValidators = new LinkedList<>();
    private final List<RequestHandler> requestHandlers = new LinkedList<>();

    public CoreProtocol() {
        initValidators(requestValidators);
        initRequestHandlers(requestHandlers);
    }

    @Override
    public String getName() {
        return "core";
    }

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest servletRequest, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws TusException {
        for (RequestValidator requestValidator : requestValidators) {
            if(requestValidator.supports(method)) {
                requestValidator.validate(method, servletRequest, uploadStorageService, idFactory);
            }
        }
    }

    @Override
    public void process(final HttpMethod method, final HttpServletRequest servletRequest, final HttpServletResponse servletResponse, final UploadStorageService uploadStorageService, final UploadIdFactory idFactory) throws IOException {
        for (RequestHandler requestHandler : requestHandlers) {
            if(requestHandler.supports(method)) {
                requestHandler.process(method, servletRequest, servletResponse, uploadStorageService, idFactory);
            }
        }
    }

    private void initValidators(final List<RequestValidator> validators) {
        validators.add(new HttpMethodValidator());
        validators.add(new TusResumableValidator());
        validators.add(new IdExistsValidator());
        validators.add(new ContentTypeValidator());
        validators.add(new UploadOffsetValidator());
        validators.add(new ContentLengthValidator());
    }

    private void initRequestHandlers(final List<RequestHandler> requestHandlers) {
        requestHandlers.add(new CoreTusResumableHandler());
        requestHandlers.add(new CoreHeadRequestHandler());
        requestHandlers.add(new CoreOptionsRequestHandler());
        requestHandlers.add(new CorePatchRequestHandler());
    }
}
