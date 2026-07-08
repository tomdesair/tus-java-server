package me.desair.tus.server.creationwithupload.validation;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidContentLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * Validator that checks that the provided Content-Length does not exceed the announced
 * Upload-Length.
 */
public class UploadContentLengthValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    Long contentLength = Utils.getLongHeader(request, HttpHeader.CONTENT_LENGTH);
    Long uploadLength = Utils.getLongHeader(request, HttpHeader.UPLOAD_LENGTH);

    if (contentLength != null && contentLength > 0 && uploadLength != null) {
      if (contentLength > uploadLength) {
        throw new InvalidContentLengthException(
            "The "
                + HttpHeader.CONTENT_LENGTH
                + " value "
                + contentLength
                + " exceeds the declared upload length "
                + uploadLength);
      }
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }
}
