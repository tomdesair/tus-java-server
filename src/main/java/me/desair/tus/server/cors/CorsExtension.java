package me.desair.tus.server.cors;

import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/** Standalone unofficial extension to add native CORS support out-of-the-box. */
public class CorsExtension extends AbstractTusExtension {

  @Override
  public String getName() {
    return "cors";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return java.util.Collections.emptyList();
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    // No validators for CORS extension
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new CorsRequestHandler());
  }
}
