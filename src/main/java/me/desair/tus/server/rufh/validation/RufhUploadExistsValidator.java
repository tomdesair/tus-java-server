package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * Request validator verifying that the target upload resource exists for status querying, data
 * appending, or cancellation requests.
 *
 * <p>Reference: Section 4.3 (Offset Retrieval), Section 4.4 (Upload Append), and Section 4.5
 * (Upload Cancellation) of draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhUploadExistsValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.HEAD.equals(method) || HttpMethod.DELETE.equals(method);
  }

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    String requestUri = request.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);
    if (uploadInfo == null) {
      throw new TusException(404, "Upload resource not found");
    }
  }
}
