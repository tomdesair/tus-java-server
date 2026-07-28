package me.desair.tus.server.digest;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/** Request handler to set option headers for RFC 9530 HTTP Digests. */
public class HttpDigestsOptionsRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.OPTIONS.equals(method);
  }

  @Override
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    String supportedAlgs = ChecksumAlgorithm.getSupportedHttpDigestAlgorithmsHeaderValue();
    servletResponse.setHeader(HttpHeader.WANT_CONTENT_DIGEST, supportedAlgs);
    servletResponse.setHeader(HttpHeader.WANT_REPR_DIGEST, supportedAlgs);
    return null;
  }
}
