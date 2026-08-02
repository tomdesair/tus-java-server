package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * Request validator verifying that the target upload resource exists for status querying, data
 * appending, or cancellation requests.
 *
 * <p>Reference: Section 4.3 (Offset Retrieval), Section 4.4 (Upload Append), and Section 4.5
 * (Upload Cancellation) of draft-ietf-httpbis-resumable-upload-12.
 */
public class RufhUploadExistsValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.HEAD.equals(method)
        || HttpMethod.GET.equals(method)
        || HttpMethod.PATCH.equals(method)
        || HttpMethod.DELETE.equals(method);
  }

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    if (request == null || uploadStorageService == null) {
      return;
    }

    if (Utils.isCreationEndpoint(request, uploadStorageService)) {
      return;
    }

    String requestUri = request.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);
    if (uploadInfo == null || uploadInfo.isExpired()) {
      throw new UploadNotFoundException("Upload resource not found");
    }
  }
}
