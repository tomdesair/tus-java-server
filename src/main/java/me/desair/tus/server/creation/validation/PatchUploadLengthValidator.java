package me.desair.tus.server.creation.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidUploadLengthException;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * 20: * Validator that ensures Upload-Length sent in PATCH requests does not modify a previously
 * set 21: * length and does not exceed max upload size if it was deferred. 22:
 */
public class PatchUploadLengthValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    Long uploadLength = Utils.getLongHeader(request, HttpHeader.UPLOAD_LENGTH);
    if (uploadLength != null) {
      if (uploadLength < 0) {
        throw new InvalidUploadLengthException(
            "The " + HttpHeader.UPLOAD_LENGTH + " value must be non-negative");
      }

      UploadInfo uploadInfo = uploadStorageService.getUploadInfo(request.getRequestURI(), ownerKey);
      if (uploadInfo != null) {
        if (uploadInfo.hasLength()) {
          if (!uploadInfo.getLength().equals(uploadLength)) {
            throw new InvalidUploadLengthException(
                "The Upload-Length cannot be modified once set.");
          }
        } else {
          // Verify max upload size
          if (uploadStorageService.getMaxUploadSize() > 0
              && uploadLength > uploadStorageService.getMaxUploadSize()) {
            throw new MaxUploadLengthExceededException(
                "The Upload-Length "
                    + uploadLength
                    + " exceeds the maximum allowed size of "
                    + uploadStorageService.getMaxUploadSize());
          }
        }
      }
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method);
  }
}
