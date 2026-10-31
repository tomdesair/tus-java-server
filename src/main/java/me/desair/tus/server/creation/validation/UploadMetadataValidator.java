package me.desair.tus.server.creation.validation;

import jakarta.servlet.http.HttpServletRequest;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidUploadMetadataException;
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

  public static final int MAX_METADATA_HEADER_LENGTH = 65_536;

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException {

    String metadata = Utils.getHeader(request, HttpHeader.UPLOAD_METADATA);
    if (StringUtils.isNotBlank(metadata)) {
      if (metadata.length() > MAX_METADATA_HEADER_LENGTH) {
        throw new InvalidUploadMetadataException(
            "Upload-Metadata header exceeds maximum allowed length of "
                + MAX_METADATA_HEADER_LENGTH
                + " characters");
      }
      String[] pairs = metadata.split(",");
      for (String pair : pairs) {
        pair = pair.trim();
        if (StringUtils.isBlank(pair)) {
          throw new InvalidUploadMetadataException("Upload-Metadata cannot contain empty pairs");
        }

        String[] keyValue = pair.split(" ");
        if (keyValue.length > 2) {
          throw new InvalidUploadMetadataException(
              "Upload-Metadata key-value pairs must be separated by a single space");
        }

        String key = keyValue[0];
        if (!isValidKey(key)) {
          throw new InvalidUploadMetadataException(
              "Upload-Metadata key must not be empty and must not contain spaces or commas");
        }

        if (keyValue.length == 2) {
          String value = keyValue[1];
          if (!Base64.isBase64(value)) {
            throw new InvalidUploadMetadataException(
                "Upload-Metadata value must be a valid Base64 encoded string");
          }
        }
      }
    }
  }

  private boolean isValidKey(String key) {
    for (char c : key.toCharArray()) {
      // The key MUST NOT contain spaces and commas and MUST NOT be empty (Tus 1.0.0 spec).
      // Commas and single spaces are already separated during parsing; reject any other whitespace.
      if (Character.isWhitespace(c)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }
}
