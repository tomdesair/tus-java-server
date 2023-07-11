package me.desair.tus.server.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.core.validation.ContentLengthValidator;
import me.desair.tus.server.core.validation.ContentTypeValidator;
import me.desair.tus.server.core.validation.HttpMethodValidator;
import me.desair.tus.server.core.validation.IdExistsValidator;
import me.desair.tus.server.core.validation.TusResumableValidator;
import me.desair.tus.server.core.validation.UploadOffsetValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * The core protocol describes how to resume an interrupted upload. It assumes that you already have
 * a URL for the upload, usually created via the Creation extension. All Clients and Servers MUST
 * implement the core protocol.
 */
public class CoreProtocol extends AbstractTusExtension {

  @Override
  public String getName() {
    return "core";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.HEAD, HttpMethod.PATCH);
  }

  @Override
  protected void initValidators(List<RequestValidator> validators) {
    validators.add(new HttpMethodValidator());
    validators.add(new TusResumableValidator());
    validators.add(new IdExistsValidator());
    validators.add(new ContentTypeValidator());
    validators.add(new UploadOffsetValidator());
    validators.add(new ContentLengthValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new CoreDefaultResponseHeadersHandler());
    requestHandlers.add(new CoreHeadRequestHandler());
    requestHandlers.add(new CorePatchRequestHandler());
    requestHandlers.add(new CoreOptionsRequestHandler());
  }
}
