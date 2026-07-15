package me.desair.tus.server.creation.validation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;

/**
 * Validator that ensures the Upload-Metadata header adheres to formatting guidelines and has valid
 * Base64 values.
 */
public class UploadMetadataValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    String metadata = Utils.getHeader(request, HttpHeader.UPLOAD_METADATA);
    if (StringUtils.isNotBlank(metadata)) {
      String[] pairs = metadata.split(",");
      for (String pair : pairs) {
        pair = pair.trim();
        if (StringUtils.isBlank(pair)) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Upload-Metadata cannot contain empty pairs");
        }

        String[] keyValue = pair.split(" ");
        if (keyValue.length > 2) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST,
              "Upload-Metadata key-value pairs must be separated by a single space");
        }

        String key = keyValue[0];
        if (StringUtils.isBlank(key)) {
          throw new TusException(
              HttpServletResponse.SC_BAD_REQUEST, "Upload-Metadata key cannot be empty");
        }

        if (keyValue.length == 2) {
          String value = keyValue[1];
          if (!Base64.isBase64(value)) {
            throw new TusException(
                HttpServletResponse.SC_BAD_REQUEST,
                "Upload-Metadata value must be a valid Base64 encoded string");
          }
        }
      }
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }
}
