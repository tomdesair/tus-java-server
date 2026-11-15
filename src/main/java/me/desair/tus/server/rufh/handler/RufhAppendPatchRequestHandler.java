package me.desair.tus.server.rufh.handler;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.MinUploadLengthNotReachedException;
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

    boolean hadLengthInitially = uploadInfo.hasLength();
    long initialOffset = uploadInfo.getOffset() != null ? uploadInfo.getOffset() : 0L;
    long contentLength = servletRequest.getContentLengthLong();
    Long providedLength =
        StructuredHeaderUtil.parseInteger(servletRequest.getHeader(HttpHeader.UPLOAD_LENGTH));
    // Persist an explicitly supplied Upload-Length on a deferred-length upload before appending
    // so progress and completion checks see the declared length; only fall back to Content-Length
    // when Upload-Complete: ?1 completes a deferred upload without an Upload-Length header.
    boolean derivedLengthFromComplete = false;
    if (!hadLengthInitially && providedLength != null && providedLength >= 0) {
      uploadInfo.setLength(providedLength);
      uploadStorageService.update(uploadInfo);
    } else if (Boolean.TRUE.equals(uploadComplete) && !hadLengthInitially && contentLength >= 0) {
      uploadInfo.setLength(initialOffset + contentLength);
      uploadStorageService.update(uploadInfo);
      derivedLengthFromComplete = true;
    }

    InputStream is = servletRequest.getContentInputStream();
    InterruptibleInputStream interruptibleStream = null;
    if (is != null) {
      interruptibleStream = new InterruptibleInputStream(is);
      if (uploadLockingService != null) {
        uploadLockingService.registerInputStream(requestUri, interruptibleStream);
      }
      is = interruptibleStream;
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
        UploadInfo refreshed = uploadStorageService.getUploadInfo(requestUri, ownerKey);
        if (refreshed != null) {
          uploadInfo = refreshed;
        }
        if (interruptibleStream.isInterrupted()) {
          log.info(
              "RUFH append request for URI {} was interrupted by locking service contention; "
                  + "saved partial upload up to offset {}",
              requestUri,
              uploadInfo.getOffset());
        } else {
          // If an un-interrupted network error aborted a deferred-length append that temporarily
          // set length from Content-Length + Upload-Complete: ?1, restore length to null before
          // rethrowing so the upload remains open with deferred length.
          if (derivedLengthFromComplete && uploadInfo != null) {
            uploadInfo.setLength(null);
            uploadStorageService.update(uploadInfo);
          }
          throw e;
        }
      } finally {
        if (uploadLockingService != null) {
          uploadLockingService.unregisterInputStream(requestUri);
        }
      }
    }

    // Detect whether stream was prematurely interrupted (e.g. concurrent contention or socket drop)
    // or fewer bytes arrived than expected by Content-Length.
    boolean streamInterrupted =
        (interruptibleStream != null && interruptibleStream.isInterrupted())
            || (contentLength >= 0 && (uploadInfo.getOffset() - initialOffset) < contentLength);
    if (streamInterrupted) {
      // Mark the request as interrupted so Content-Digest validation preserves saved partial bytes
      // instead of rejecting the truncated chunk.
      servletRequest.setStreamInterrupted(true);
    }

    // Per RUFH §4.2: An upload must only be marked complete if the stream completed uninterrupted.
    // If the stream was interrupted, we acknowledge partial progress and allow subsequent PATCH
    // resumes.
    boolean isFinished =
        !streamInterrupted
            && (Boolean.TRUE.equals(uploadComplete) || isUploadCompleted(uploadInfo));
    if (isFinished) {
      Long minSize = uploadStorageService.getMinSize();
      if (minSize != null && minSize > 0 && uploadInfo.getOffset() < minSize) {
        throw new MinUploadLengthNotReachedException(
            "The upload length ("
                + uploadInfo.getOffset()
                + ") is smaller than the minimum allowed size ("
                + minSize
                + ")");
      }
      uploadInfo.setLength(uploadInfo.getOffset());
      uploadStorageService.update(uploadInfo);
    } else if (streamInterrupted && derivedLengthFromComplete) {
      // If the stream was interrupted on a deferred-length upload whose length was only tentatively
      // inferred from Content-Length, reset length to null so it remains open for resume.
      uploadInfo.setLength(null);
      uploadStorageService.update(uploadInfo);
    }

    servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    servletResponse.setHeader(
        HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(isFinished));

    if (isFinished) {
      servletResponse.setStatus(HttpServletResponse.SC_OK);
    } else {
      servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
    return null;
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return !uploadInfo.isUploadInProgress();
  }
}
