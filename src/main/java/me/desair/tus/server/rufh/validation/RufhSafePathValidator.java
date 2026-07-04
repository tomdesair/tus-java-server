package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * Request validator that checks the request URI for unsafe path traversal components (e.g. ".." or
 * null bytes).
 *
 * <p>Reference: Section 13 (Security Considerations) of draft-ietf-httpbis-resumable-upload-11:
 * "Uploaded representation data and its metadata are untrusted input."
 */
public class RufhSafePathValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return method != null;
  }

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    String path = request.getRequestURI();
    if (path != null && (path.contains("..") || path.contains("\0"))) {
      throw new TusException(400, "Invalid or unsafe path component: " + path);
    }
  }
}
