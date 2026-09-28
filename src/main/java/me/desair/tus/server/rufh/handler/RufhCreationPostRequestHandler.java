package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.util.RufhInterimResponseUtil;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.Strings;

/**
 * Request handler for upload creation requests via HTTP POST, PUT, or PATCH.
 *
 * <p>Handles upload initialization, payload byte streaming, lock registration via {@link
 * InterruptibleInputStream}, and response headers.
 *
 * <p>Reference: Section 4.2 (Upload Creation) & Section 4.2.2 (Server Behavior) of
 * draft-ietf-httpbis-resumable-upload-12.
 */
public class RufhCreationPostRequestHandler extends AbstractRequestHandler {

  public RufhCreationPostRequestHandler() {
    // Default constructor
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method)
        || HttpMethod.PUT.equals(method)
        || HttpMethod.PATCH.equals(method);
  }

  @Override
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    if (HttpMethod.PATCH.equals(method)
        && Utils.isExistingUploadResource(servletRequest, uploadStorageService, ownerKey)) {
      // Existing upload on PATCH request is handled by RufhAppendPatchRequestHandler
      return null;
    }

    UploadInfo preCreatedUploadInfo =
        (UploadInfo)
            servletRequest.getAttribute(RufhInterimResponseUtil.PRE_CREATED_UPLOAD_INFO_ATTR);

    String uploadLengthHeader = servletRequest.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long uploadLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);

    String uploadCompleteHeader = servletRequest.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);

    // Per RUFH §4.2.1: If upload length is deferred upon creation and the client completes
    // the upload in the creation request via Upload-Complete: ?1, the total length is derived
    // from Content-Length.
    long contentLength = servletRequest.getContentLengthLong();
    Long announcedLength = uploadLength;
    if (announcedLength == null && Boolean.TRUE.equals(uploadComplete) && contentLength >= 0) {
      announcedLength = contentLength;
    }

    UploadInfo uploadInfo;
    if (preCreatedUploadInfo != null) {
      uploadInfo = preCreatedUploadInfo;
      if (announcedLength != null && announcedLength >= 0) {
        uploadInfo.setLength(announcedLength);
      }
      uploadStorageService.update(uploadInfo);
    } else {
      uploadInfo = new UploadInfo();
      if (announcedLength != null && announcedLength >= 0) {
        uploadInfo.setLength(announcedLength);
      }
      uploadInfo = uploadStorageService.create(uploadInfo, ownerKey);
    }

    String uploadUri =
        Utils.getUploadUriOnCreation(uploadInfo, servletRequest, uploadStorageService);

    // Per RUFH §4.1.4: "This limit does not apply to upload creation requests with no content,
    // or to requests completing the upload by including the Upload-Complete: ?1 header field."
    // An empty creation request carries no body (contentLength <= 0 without chunked encoding).
    // In servlet requests without a body, getContentLengthLong() returns -1. We must verify
    // that actual payload content is present before invoking storageService.append(); otherwise,
    // sending a 0-byte stream to backends with minAppendSize configured (e.g. S3 or Azure Blob)
    // would trigger MinAppendSizeNotMetException on an empty creation request.
    boolean hasContent =
        contentLength > 0
            || (contentLength < 0
                && servletRequest.getHeader(HttpHeader.TRANSFER_ENCODING) != null
                && Strings.CI.contains(
                    servletRequest.getHeader(HttpHeader.TRANSFER_ENCODING), "chunked"));

    InputStream is = servletRequest.getContentInputStream();
    if (is != null && hasContent) {
      if (uploadLockingService != null) {
        InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(is);
        uploadLockingService.registerInputStream(uploadUri, interruptibleStream);
        is = interruptibleStream;
      }
      UploadInfo appended = uploadStorageService.append(uploadInfo, is);
      if (appended != null) {
        uploadInfo = appended;
      }
    }

    boolean isFinished = Boolean.TRUE.equals(uploadComplete) || isUploadCompleted(uploadInfo);
    if (isFinished) {
      uploadInfo.setLength(uploadInfo.getOffset());
      uploadStorageService.update(uploadInfo);
      servletResponse.setStatus(200);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(true));
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    } else {
      servletResponse.setStatus(201);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(false));
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    }
    return null;
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return !uploadInfo.isUploadInProgress();
  }
}
