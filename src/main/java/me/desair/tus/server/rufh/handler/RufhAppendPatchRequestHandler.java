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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request handler for data append requests via HTTP PATCH.
 *
 * <p>Appends request payload bytes to an existing upload resource, registers locking via {@link
 * InterruptibleInputStream}, updates completion status, and sets Upload-Offset, Upload-Complete,
 * and Upload-Draft response headers.
 *
 * <p>Reference: Section 4.4.2 (Append Response) & Appendix B (Draft Version Identification) of
 * draft-ietf-httpbis-resumable-upload-12.
 */
public class RufhAppendPatchRequestHandler extends AbstractRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(RufhAppendPatchRequestHandler.class);

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method);
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

    String requestUri = servletRequest.getRequestURI();
    UploadInfo uploadInfo = uploadStorageService.getUploadInfo(requestUri, ownerKey);

    // If upload is null, this is a PATCH creation request and was handled by
    // RufhCreationPostRequestHandler
    if (uploadInfo == null) {
      return null;
    }

    String uploadCompleteHeader = servletRequest.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);

    // Per RUFH §4.2.1: If upload length was deferred and the client completes the upload
    // via Upload-Complete: ?1, derive and set the total length from current offset +
    // Content-Length.
    long contentLength = servletRequest.getContentLengthLong();
    if (Boolean.TRUE.equals(uploadComplete) && !uploadInfo.hasLength() && contentLength >= 0) {
      long currentOffset = uploadInfo.getOffset() != null ? uploadInfo.getOffset() : 0L;
      uploadInfo.setLength(currentOffset + contentLength);
      uploadStorageService.update(uploadInfo);
    }

    InputStream is = servletRequest.getContentInputStream();
    InterruptibleInputStream interruptibleStream = null;
    if (is != null) {
      if (uploadLockingService != null) {
        interruptibleStream = new InterruptibleInputStream(is);
        uploadLockingService.registerInputStream(requestUri, interruptibleStream);
        is = interruptibleStream;
      }
      try {
        UploadInfo appended = uploadStorageService.append(uploadInfo, is);
        if (appended != null) {
          uploadInfo = appended;
        }
      } catch (IOException e) {
        // When an append stream is interrupted by the locking service watchdog or a concurrent
        // release request, the storage backend commits all bytes received so far and updates
        // the offset in storage. We reload the updated UploadInfo and acknowledge the partial
        // append rather than propagating an unhandled error.
        if (interruptibleStream != null && interruptibleStream.isInterrupted()) {
          log.info(
              "RUFH append request for URI {} was interrupted by locking service contention; "
                  + "saved partial upload up to offset {}",
              requestUri,
              uploadInfo != null ? uploadInfo.getOffset() : "unknown");
          UploadInfo refreshed = uploadStorageService.getUploadInfo(requestUri, ownerKey);
          if (refreshed != null) {
            uploadInfo = refreshed;
          }
        } else {
          throw e;
        }
      }
    }

    boolean isFinished = Boolean.TRUE.equals(uploadComplete) || isUploadCompleted(uploadInfo);
    if (isFinished) {
      uploadInfo.setLength(uploadInfo.getOffset());
      uploadStorageService.update(uploadInfo);
    }

    servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    servletResponse.setHeader(
        HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(isFinished));

    if (isFinished) {
      servletResponse.setStatus(200);
    } else {
      servletResponse.setStatus(204);
    }
    return null;
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return !uploadInfo.isUploadInProgress();
  }
}
