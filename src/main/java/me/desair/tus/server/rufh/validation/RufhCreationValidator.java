package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InconsistentUploadLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.StructuredHeaderUtil;

/**
 * Request validator checking creation request limits (max upload length and max append payload
 * size) and length consistency.
 *
 * <p>Reference: Section 4.1.3 (Length) & Section 4.2 (Upload Creation) of
 * draft-ietf-httpbis-resumable-upload-11: "If indicators (1) [Upload-Complete: ?1 with
 * Content-Length] and (2) [Upload-Length] are both present in the same request, their indicated
 * lengths MUST match."
 */
public class RufhCreationValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method) || HttpMethod.PUT.equals(method);
  }

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    String uploadLengthHeader = request.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long uploadLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);
    String uploadCompleteHeader = request.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);
    long contentLength = request.getContentLengthLong();

    // Section 4.1.3: Validate consistency between Upload-Length and Content-Length when
    // Upload-Complete: ?1
    if (uploadLength != null && Boolean.TRUE.equals(uploadComplete) && contentLength >= 0) {
      if (uploadLength != contentLength) {
        throw new InconsistentUploadLengthException(
            "The provided Upload-Length ("
                + uploadLength
                + ") does not match Content-Length ("
                + contentLength
                + ")");
      }
    }

    long maxUploadSize = uploadStorageService.getMaxUploadSize();
    if (maxUploadSize > 0 && uploadLength != null && uploadLength > maxUploadSize) {
      throw new TusException(413, "The requested upload length exceeds the maximum allowed size");
    }

    Long maxAppendSize = uploadStorageService.getMaxAppendSize();
    if (maxAppendSize != null
        && maxAppendSize > 0
        && contentLength > 0
        && contentLength > maxAppendSize) {
      throw new TusException(
          413,
          "The request payload size ("
              + contentLength
              + ") exceeds the maximum allowed append size ("
              + maxAppendSize
              + ")");
    }
  }
}
