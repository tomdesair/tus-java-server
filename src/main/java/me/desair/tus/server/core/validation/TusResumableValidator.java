package me.desair.tus.server.core.validation;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.TusFileUploadService;
import me.desair.tus.server.exception.InvalidTusResumableException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;

/**
 * Class that will validate if the tus version in the request corresponds to our implementation
 * version <br>
 * The Tus-Resumable header MUST be included in every request and response except for OPTIONS
 * requests. The value MUST be the version of the protocol used by the Client or the Server. If the
 * the version specified by the Client is not supported by the Server, it MUST respond with the 412
 * Precondition Failed status and MUST include the Tus-Version header into the response. In
 * addition, the Server MUST NOT process the request. <br>
 * (https://tus.io/protocols/resumable-upload.html#tus-resumable)
 */
public class TusResumableValidator implements RequestValidator {

  /** Validate tus protocol version. */
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    String requestVersion = Utils.getHeader(request, HttpHeader.TUS_RESUMABLE);
    if (!StringUtils.equals(requestVersion, TusFileUploadService.TUS_API_VERSION)) {
      throw new InvalidTusResumableException(
          "This server does not support tus protocol version " + requestVersion);
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return !HttpMethod.OPTIONS.equals(method) && !HttpMethod.GET.equals(method);
  }
}
