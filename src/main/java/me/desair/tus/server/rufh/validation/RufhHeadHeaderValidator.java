package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * Request validator checking that HEAD status requests do not contain Upload-Offset or
 * Upload-Complete header fields.
 *
 * <p>Reference: Section 4.3.1 (Client Behavior - Offset Retrieval) of
 * draft-ietf-httpbis-resumable-upload-11: "The request MUST NOT contain Upload-Offset or
 * Upload-Complete header fields."
 */
public class RufhHeadHeaderValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.HEAD.equals(method);
  }

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    if (request.getHeader(HttpHeader.UPLOAD_OFFSET) != null
        || request.getHeader(HttpHeader.UPLOAD_COMPLETE) != null) {
      throw new TusException(
          400, "HEAD request MUST NOT contain Upload-Offset or Upload-Complete header field");
    }
  }
}
