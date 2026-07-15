package me.desair.tus.server.rufh;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.rufh.handler.RufhAppendPatchRequestHandler;
import me.desair.tus.server.rufh.handler.RufhCreationPostRequestHandler;
import me.desair.tus.server.rufh.handler.RufhDeleteRequestHandler;
import me.desair.tus.server.rufh.handler.RufhErrorHandler;
import me.desair.tus.server.rufh.handler.RufhHeadRequestHandler;
import me.desair.tus.server.rufh.handler.RufhOptionsRequestHandler;
import me.desair.tus.server.rufh.handler.RufhResponseHeadersHandler;
import me.desair.tus.server.rufh.validation.RufhAppendValidator;
import me.desair.tus.server.rufh.validation.RufhCreationValidator;
import me.desair.tus.server.rufh.validation.RufhHeadHeaderValidator;
import me.desair.tus.server.rufh.validation.RufhSafePathValidator;
import me.desair.tus.server.rufh.validation.RufhUploadExistsValidator;
import me.desair.tus.server.util.AbstractTusExtension;

/**
 * Protocol extension implementing the official IETF Resumable Uploads for HTTP specification
 * (draft-ietf-httpbis-resumable-upload:
 * https://datatracker.ietf.org/doc/draft-ietf-httpbis-resumable-upload/).
 *
 * <p>Delegates validation and request processing to modular {@link RequestValidator} and {@link
 * RequestHandler} implementations registered during initialization.
 */
public class ResumableUploadsForHttpProtocol extends AbstractTusExtension {

  public static final String EXTENSION_NAME = "resumable-uploads-for-http";

  private InterimResponseStrategy interimResponseStrategy = new NoOpInterimResponseStrategy();

  /** Construct a default ResumableUploadsForHttpProtocol extension instance. */
  public ResumableUploadsForHttpProtocol() {
    super();
  }

  /**
   * Configure an optional interim response strategy (e.g. for HTTP 104 interim responses).
   *
   * @param interimResponseStrategy The interim response strategy to use
   * @return Current extension instance for method chaining
   */
  public ResumableUploadsForHttpProtocol withInterimResponseStrategy(
      InterimResponseStrategy interimResponseStrategy) {
    if (interimResponseStrategy != null) {
      this.interimResponseStrategy = interimResponseStrategy;
    }
    return this;
  }

  @Override
  public String getName() {
    return EXTENSION_NAME;
  }

  @Override
  public Collection<HttpMethod> getMinimalSupportedHttpMethods() {
    return Arrays.asList(
        HttpMethod.OPTIONS, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE);
  }

  @Override
  public boolean isApplicable(HttpMethod method, ProtocolVersion version) {
    if (HttpMethod.OPTIONS.equals(method)) {
      return true;
    }
    return version == ProtocolVersion.RUFH;
  }

  @Override
  public boolean mustReprocessOnError(HttpMethod method, ProtocolVersion version) {
    return version == ProtocolVersion.RUFH;
  }

  @Override
  protected void initValidators(List<RequestValidator> requestValidators) {
    requestValidators.add(new RufhSafePathValidator());
    requestValidators.add(new RufhUploadExistsValidator());
    requestValidators.add(new RufhHeadHeaderValidator());
    requestValidators.add(new RufhCreationValidator());
    requestValidators.add(new RufhAppendValidator());
  }

  @Override
  protected void initRequestHandlers(List<RequestHandler> requestHandlers) {
    requestHandlers.add(new RufhResponseHeadersHandler());
    requestHandlers.add(new RufhOptionsRequestHandler());
    requestHandlers.add(new RufhHeadRequestHandler());
    requestHandlers.add(new RufhCreationPostRequestHandler(interimResponseStrategy));
    requestHandlers.add(new RufhAppendPatchRequestHandler());
    requestHandlers.add(new RufhDeleteRequestHandler());
    requestHandlers.add(new RufhErrorHandler());
  }
}
