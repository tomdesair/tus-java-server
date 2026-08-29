package me.desair.tus.server.creationwithupload.validation;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidContentTypeException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * Validator that checks that if Content-Length is greater than zero on a POST request, the
 * Content-Type must be application/offset+octet-stream.
 */
public class PostContentTypeValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    Long contentLength = Utils.getLongHeader(request, HttpHeader.CONTENT_LENGTH);
    if (contentLength != null && contentLength > 0) {
      String contentType = Utils.getHeader(request, HttpHeader.CONTENT_TYPE);
      if (!Utils.isMediaType(contentType, HttpHeader.CONTENT_TYPE_OFFSET_OCTET_STREAM)) {
        throw new InvalidContentTypeException(
            "The "
                + HttpHeader.CONTENT_TYPE
                + " header must contain value "
                + HttpHeader.CONTENT_TYPE_OFFSET_OCTET_STREAM);
      }
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }
}
