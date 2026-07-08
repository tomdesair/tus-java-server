package me.desair.tus.server.creationwithupload;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.creation.CreationExtension;
import me.desair.tus.server.creationwithupload.validation.PostContentTypeValidator;
import me.desair.tus.server.creationwithupload.validation.UploadContentLengthValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * The Client and the Server SHOULD implement the creation-with-upload extension. This extension
 * allows combining upload creation and sending initial payload in one request.
 */
public class CreationWithUploadExtension extends AbstractTusExtension {

  public CreationWithUploadExtension(CreationExtension creationExtension) {
    Objects.requireNonNull(creationExtension, "CreationExtension cannot be null");
    creationExtension.setCreationWithUploadEnabled(true);
  }

  @Override
  public String getName() {
    return "creation-with-upload";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.POST);
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    requestValidators.add(new PostContentTypeValidator());
    requestValidators.add(new UploadContentLengthValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new CreationWithUploadOptionsRequestHandler());
    requestHandlers.add(new CreationWithUploadPostRequestHandler());
  }
}
