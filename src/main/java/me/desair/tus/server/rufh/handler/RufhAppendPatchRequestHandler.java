package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for data append requests via HTTP PATCH.
 *
 * <p>Appends request payload bytes to an existing upload resource, registers locking via {@link
 * InterruptibleInputStream}, updates completion status, and sets Upload-Offset, Upload-Complete,
 * and Upload-Draft response headers.
 *
 * <p>Reference: Section 4.4.2 (Append Response) & Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhAppendPatchRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {
    process(method, servletRequest, servletResponse, uploadStorageService, null, ownerKey);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey)
      throws IOException, TusException {

    String requestUri = servletRequest.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);

    // If upload is null, this is a PATCH creation request and was handled by
    // RufhCreationPostRequestHandler
    if (uploadInfo == null) {
      return;
    }

    String uploadCompleteHeader = servletRequest.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);

    InputStream is = servletRequest.getContentInputStream();
    if (is != null) {
      if (uploadLockingService != null) {
        InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(is);
        uploadLockingService.registerInputStream(requestUri, interruptibleStream);
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
    }

    servletResponse.setHeader(HttpHeader.UPLOAD_DRAFT, "11");
    servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    servletResponse.setHeader(
        HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(isFinished));

    if (isFinished) {
      servletResponse.setStatus(200);
    } else {
      servletResponse.setStatus(204);
    }
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return uploadInfo != null && !uploadInfo.isUploadInProgress();
  }
}
