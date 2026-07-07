package me.desair.tus.server.digest;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.digest.validation.HttpDigestsValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * Protocol extension implementing the RFC 9530 HTTP Digests for integrity verification. Supported
 * on RUFH protocol version.
 */
public class HttpDigestsExtension extends AbstractTusExtension {

  @Override
  public String getName() {
    return "http-digests";
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(HttpMethod.OPTIONS, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);
  }

  @Override
  public boolean isApplicable(HttpMethod method, ProtocolVersion version) {
    if (HttpMethod.OPTIONS.equals(method)) {
      return true;
    }
    return version == ProtocolVersion.RUFH;
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    requestValidators.add(new HttpDigestsValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new HttpDigestsOptionsRequestHandler());
    requestHandlers.add(new HttpDigestsPostPutPatchRequestHandler());
  }
}
