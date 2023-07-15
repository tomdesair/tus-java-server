package me.desair.tus.server.creation;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.creation.validation.PostEmptyRequestValidator;
import me.desair.tus.server.creation.validation.PostUriValidator;
import me.desair.tus.server.creation.validation.UploadDeferLengthValidator;
import me.desair.tus.server.creation.validation.UploadLengthValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * The Client and the Server SHOULD implement the upload creation extension. If the Server supports
 * this extension.
 */
public class CreationExtension extends AbstractTusExtension {

  @Override
  public String getName() {
    return "creation";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.HEAD, HttpMethod.PATCH, HttpMethod.POST);
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    requestValidators.add(new PostUriValidator());
    requestValidators.add(new PostEmptyRequestValidator());
    requestValidators.add(new UploadDeferLengthValidator());
    requestValidators.add(new UploadLengthValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new CreationHeadRequestHandler());
    requestHandlers.add(new CreationPatchRequestHandler());
    requestHandlers.add(new CreationPostRequestHandler());
    requestHandlers.add(new CreationOptionsRequestHandler());
  }
}
