package me.desair.tus.server.expiration;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/** The Server MAY remove unfinished uploads once they expire. */
public class ExpirationExtension extends AbstractTusExtension {

  @Override
  public String getName() {
    return "expiration";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.PATCH, HttpMethod.POST);
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    // No validators
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new ExpirationOptionsRequestHandler());
    requestHandlers.add(new ExpirationRequestHandler());
  }
}
