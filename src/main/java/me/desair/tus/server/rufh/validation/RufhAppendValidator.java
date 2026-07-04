package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.StructuredHeaderUtil;
import org.apache.commons.lang3.Strings;

/**
 * Request validator checking data append requests via HTTP PATCH.
 *
 * <p>Validates Content-Type headers, resource existence, completed upload status, payload limits,
 * Upload-Offset equality, and Upload-Length compliance.
 *
 * <p>Reference: Section 4.4.1 (Append Request) & Section 4.4.2 (Append Response) of
 * draft-ietf-httpbis-resumable-upload-11:
 *
 * <ul>
 *   <li>"If the Upload-Offset request header field value does not match the current offset... the
 *       upload resource MUST reject the request with a 409 (Conflict) status code."
 *   <li>"If the length is known, the server MUST prevent the offset from exceeding the upload
 *       length by rejecting the request once the offset exceeds the length..."
 * </ul>
 */
public class RufhAppendValidator implements RequestValidator {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method);
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

    // If upload is null, this might be a PATCH creation request. Creation validation handles it.
    if (uploadInfo == null) {
      return;
    }

    String contentType = request.getHeader(HttpHeader.CONTENT_TYPE);
    if (!Strings.CS.startsWith(contentType, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD)
        && !Strings.CS.startsWith(contentType, "application/offset+octet-stream")) {
      throw new TusException(415, "Unsupported Content-Type for append request");
    }

    if (!uploadInfo.isUploadInProgress()) {
      throw new TusException(400, "Upload resource is already completed");
    }

    Long maxAppendSize = uploadStorageService.getMaxAppendSize();
    long contentLength = request.getContentLengthLong();
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

    String offsetHeader = request.getHeader(HttpHeader.UPLOAD_OFFSET);
    Long providedOffset = StructuredHeaderUtil.parseInteger(offsetHeader);
    if (providedOffset == null) {
      throw new TusException(400, "Missing or invalid Upload-Offset header");
    }

    long currentOffset = uploadInfo.getOffset();
    if (providedOffset != currentOffset) {
      throw new TusException(
          409,
          "Upload-Offset " + providedOffset + " does not match server offset " + currentOffset);
    }

    // Section 4.4.2: Prevent offset from exceeding upload length if length is known
    if (uploadInfo.hasLength() && contentLength > 0) {
      if (currentOffset + contentLength > uploadInfo.getLength()) {
        throw new TusException(
            400,
            "Appended content length ("
                + contentLength
                + ") pushes total offset past declared upload length ("
                + uploadInfo.getLength()
                + ")");
      }
    }

    // Section 4.1.3: Validate consistency of Upload-Length if provided in append request
    String uploadLengthHeader = request.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long providedLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);
    if (providedLength != null && uploadInfo.hasLength()) {
      if (!providedLength.equals(uploadInfo.getLength())) {
        throw new TusException(
            400,
            "Provided Upload-Length ("
                + providedLength
                + ") does not match existing upload length ("
                + uploadInfo.getLength()
                + ")");
      }
    }
  }
}
