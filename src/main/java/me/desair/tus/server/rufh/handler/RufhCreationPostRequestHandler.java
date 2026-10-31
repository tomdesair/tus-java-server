package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.util.RufhInterimResponseUtil;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(RufhCreationPostRequestHandler.class);

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
    // An empty creation request carries no body. In requests without Content-Length (e.g.
    // HTTP/1.1 chunked encoding, HTTP/2 streaming, or upstream proxies stripping hop-by-hop
    // headers per RFC 9112 §6.3), getContentLengthLong() returns -1.
    // servletRequest.hasContent() peeks 1 byte via PushbackInputStream to accurately detect
    // whether actual payload bytes are present before invoking storageService.append();
    // otherwise, passing an empty stream to backends with minAppendSize would erroneously reject an
    // empty creation request.
    boolean hasContent = servletRequest != null && servletRequest.hasContent();

    InterruptibleInputStream interruptibleStream = null;
    if (hasContent) {
      InputStream is = servletRequest.getContentInputStream();
      if (is != null) {
        // Acquire the distributed lock for the newly created upload resource if not already locked
        // by the outer request so concurrent requests across replicas coordinate via lock
        // contention and the registered stream is guaranteed to be cleaned up when the lock closes.
        boolean needsLock =
            uploadLockingService != null
                && uploadInfo != null
                && uploadInfo.getId() != null
                && !uploadLockingService.isLocked(uploadInfo.getId());
        try (UploadLock ignored =
            needsLock ? uploadLockingService.lockUploadByUri(uploadUri) : null) {
          interruptibleStream = new InterruptibleInputStream(is);
          if (uploadLockingService != null) {
            uploadLockingService.registerInputStream(uploadUri, interruptibleStream);
          }
          is = interruptibleStream;
          try {
            UploadInfo appended = uploadStorageService.append(uploadInfo, is);
            if (appended != null) {
              uploadInfo = appended;
            }
          } catch (IOException e) {
            // When an upload stream is interrupted by the locking service watchdog or a concurrent
            // lock contention release request (e.g. from a concurrent HEAD or DELETE), the storage
            // backend (Disk, S3, Azure) commits all bytes received up to the interruption and
            // updates the offset in storage. We reload the updated UploadInfo and acknowledge the
            // partial upload rather than propagating an unhandled error to the servlet container.
            UploadInfo refreshed = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
            if (refreshed != null) {
              uploadInfo = refreshed;
            }
            if (interruptibleStream.isInterrupted()) {
              log.info(
                  "RUFH creation request with body for URI {} was interrupted by locking service"
                      + " contention; saved partial upload up to offset {}",
                  uploadUri,
                  uploadInfo != null ? uploadInfo.getOffset() : "unknown");
            } else {
              // If the client socket dropped mid-stream on a deferred-length upload (where length
              // was temporarily derived from Content-Length + Upload-Complete: ?1), reset length
              // to null in storage before rethrowing so the upload remains resumable with
              // deferred length on subsequent PATCH requests.
              if (uploadLength == null && uploadInfo != null) {
                uploadInfo.setLength(null);
                uploadStorageService.update(uploadInfo);
              }
              throw e;
            }
          } finally {
            if (uploadLockingService != null) {
              uploadLockingService.unregisterInputStream(uploadUri);
            }
          }
        }
      }
    }

    // Detect whether stream was prematurely interrupted (e.g. concurrent contention or socket drop)
    // or fewer bytes arrived than expected by Content-Length.
    boolean streamInterrupted =
        (interruptibleStream != null && interruptibleStream.isInterrupted())
            || (contentLength >= 0 && uploadInfo != null && uploadInfo.getOffset() < contentLength);

    // Per RUFH §4.2: An upload must only be marked complete if the stream completed uninterrupted.
    // If the stream was interrupted, we acknowledge partial progress and allow subsequent PATCH
    // resumes.
    boolean isFinished =
        !streamInterrupted
            && (Boolean.TRUE.equals(uploadComplete)
                || (uploadInfo != null && isUploadCompleted(uploadInfo)));

    if (isFinished && uploadInfo != null) {
      uploadInfo.setLength(uploadInfo.getOffset());
      uploadStorageService.update(uploadInfo);
      servletResponse.setStatus(200);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(true));
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    } else {
      // If length was originally deferred (no Upload-Length header) and stream was interrupted,
      // reset length to null so that the upload remains in-progress and can be resumed via PATCH.
      if (streamInterrupted && uploadInfo != null && uploadLength == null) {
        uploadInfo.setLength(null);
        uploadStorageService.update(uploadInfo);
      }
      servletResponse.setStatus(201);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(false));
      servletResponse.setHeader(
          HttpHeader.UPLOAD_OFFSET,
          String.valueOf(uploadInfo != null ? uploadInfo.getOffset() : 0L));
    }
    return null;
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return !uploadInfo.isUploadInProgress();
  }
}
