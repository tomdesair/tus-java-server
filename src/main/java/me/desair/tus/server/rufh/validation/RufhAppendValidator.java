package me.desair.tus.server.rufh.validation;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InconsistentUploadLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadAlreadyCompletedException;
import me.desair.tus.server.exception.UploadOffsetMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Strings;

/**
 * Request validator checking data append requests via HTTP PATCH.
 *
 * <p>Validates Content-Type headers, resource existence, completed upload status, payload limits,
 * Upload-Offset equality, and Upload-Length compliance.
 *
 * <p>Reference: Section 4.4.1 (Append Request) & Section 4.4.2 (Append Response) of
 * draft-ietf-httpbis-resumable-upload-12:
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
    boolean isCreationEndpoint = Utils.isCreationEndpoint(request, uploadStorageService);
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);

    if (uploadInfo == null || uploadInfo.isExpired()) {
      if (!isCreationEndpoint) {
        throw new TusException(404, "Upload resource not found");
      }
      return;
    }

    String uploadCompleteHeader = request.getHeader(HttpHeader.UPLOAD_COMPLETE);
    if (uploadCompleteHeader == null) {
      throw new TusException(400, "PATCH append request MUST include Upload-Complete header field");
    }

    String contentType = request.getHeader(HttpHeader.CONTENT_TYPE);
    if (!Strings.CS.startsWith(contentType, HttpHeader.CONTENT_TYPE_PARTIAL_UPLOAD)
        && !Strings.CS.startsWith(contentType, "application/offset+octet-stream")) {
      throw new TusException(415, "Unsupported Content-Type for append request");
    }

    if (!uploadInfo.isUploadInProgress()) {
      try {
        // Section 4.4.2: Deactivate upload resource when append is attempted on a completed upload
        // ponytail: Terminate/deactivate upload resource per §4.4.2 when append is attempted past
        // declared length
        uploadStorageService.terminateUpload(uploadInfo);
      } catch (Exception e) {
        // Log or ignore cleanup failure
      }
      throw new UploadAlreadyCompletedException("Upload resource is already completed");
    }

    String offsetHeader = request.getHeader(HttpHeader.UPLOAD_OFFSET);
    Long providedOffset = StructuredHeaderUtil.parseInteger(offsetHeader);
    if (providedOffset == null) {
      throw new TusException(400, "Missing or invalid Upload-Offset header");
    }

    long currentOffset = uploadInfo.getOffset();
    if (providedOffset != currentOffset) {
      // Section 4.4.2: Offset Mismatch Error Response
      // "If the Upload-Offset request header field value does not match the current offset... the
      // server MUST reject
      // the request with a 409 (Conflict) status code... The response MUST include the correct
      // offset in the Upload-Offset header field."
      throw new UploadOffsetMismatchException(
          "Upload-Offset " + providedOffset + " does not match server offset " + currentOffset);
    }

    long contentLength = request.getContentLengthLong();

    // Section 4.1.4 & Section 4.7: Validate max-append-size first to reject oversized payloads with
    // 413 Payload Too Large
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

    // Section 4.4.2: Prevent offset from exceeding upload length if length is known and invalidate
    // resource
    // "the server MUST prevent the offset from exceeding the representation's length by rejecting
    // the request
    // once the offset exceeds the length, marking the upload resource invalid and rejecting any
    // further interaction with it."
    // ponytail: When appended bytes cause offset to exceed declared length (or if upload is already
    // at declared length),
    // deactivate/terminate the upload resource per §4.4.2 and reject with 409 Conflict.
    if (uploadInfo.hasLength()) {
      if (currentOffset >= uploadInfo.getLength()
          || (contentLength > 0 && currentOffset + contentLength > uploadInfo.getLength())) {
        try {
          uploadStorageService.terminateUpload(uploadInfo);
        } catch (Exception e) {
          // Log or ignore cleanup failure
        }
        throw new TusException(
            409,
            "Appended content length ("
                + contentLength
                + ") pushes total offset past declared upload length ("
                + uploadInfo.getLength()
                + ")");
      }
    }

    // Section 4.1.4: min-append-size validation with exemption for Upload-Complete: ?1
    // "This limit does not apply to upload creation requests with no content, or to requests
    // completing the upload by including the Upload-Complete: ?1 header field."
    Long minAppendSize = uploadStorageService.getMinAppendSize();
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);
    boolean isCompleteExempt = Boolean.TRUE.equals(uploadComplete);

    if (minAppendSize != null && minAppendSize > 0 && !isCompleteExempt) {
      if (contentLength < minAppendSize) {
        throw new TusException(
            400,
            "The request payload size ("
                + contentLength
                + ") is below the minimum allowed append size ("
                + minAppendSize
                + ")");
      }
    }

    // Section 4.1.3: Validate consistency of Upload-Length if provided in append request
    String uploadLengthHeader = request.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long providedLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);
    if (providedLength != null && uploadInfo.hasLength()) {
      if (!providedLength.equals(uploadInfo.getLength())) {
        throw new InconsistentUploadLengthException(
            "Provided Upload-Length ("
                + providedLength
                + ") does not match existing upload length ("
                + uploadInfo.getLength()
                + ")");
      }
    }
  }
}
