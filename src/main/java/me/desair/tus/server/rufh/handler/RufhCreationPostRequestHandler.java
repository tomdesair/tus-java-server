package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;

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
        (UploadInfo) servletRequest.getAttribute("me.desair.tus.preCreatedUploadInfo");

    String uploadLengthHeader = servletRequest.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long uploadLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);

    String uploadCompleteHeader = servletRequest.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);

    UploadInfo uploadInfo;
    if (preCreatedUploadInfo != null) {
      uploadInfo = preCreatedUploadInfo;
      if (uploadLength != null && uploadLength >= 0) {
        uploadInfo.setLength(uploadLength);
      }
      uploadStorageService.update(uploadInfo);
    } else {
      uploadInfo = new UploadInfo();
      if (uploadLength != null && uploadLength >= 0) {
        uploadInfo.setLength(uploadLength);
      }
      uploadInfo = uploadStorageService.create(uploadInfo, ownerKey);
    }

    String uploadUri =
        Utils.getUploadUriOnCreation(uploadInfo, servletRequest, uploadStorageService);

    InputStream is = servletRequest.getContentInputStream();
    if (is != null && servletRequest.getContentLengthLong() != 0) {
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
