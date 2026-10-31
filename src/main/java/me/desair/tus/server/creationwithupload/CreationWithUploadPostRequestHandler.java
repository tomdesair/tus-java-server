package me.desair.tus.server.creationwithupload;

import java.io.IOException;
import java.io.InputStream;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLock;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Request handler to process the POST request body for the creation-with-upload extension. */
public class CreationWithUploadPostRequestHandler extends AbstractRequestHandler {

  private static final Logger log =
      LoggerFactory.getLogger(CreationWithUploadPostRequestHandler.class);

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
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

    // Check if the creation request carries upload data to append in the initial request body.
    // Supports requests with Content-Length as well as chunked encoding or HTTP/2 streaming.
    boolean hasContent = servletRequest != null && servletRequest.hasContent();
    if (hasContent) {
      String location = servletResponse.getHeader(HttpHeader.LOCATION);
      if (location != null) {
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(location, ownerKey);
        if (uploadInfo != null && uploadInfo.isUploadInProgress()) {
          // A POST creation request targets the base creation URI (e.g. /files), which has no
          // UploadId when TusFileUploadService.process() initially acquires the request lock.
          // Once CreationPostRequestHandler creates the upload and assigns an UploadId, we must
          // acquire the distributed UploadLock for the new location before streaming payload
          // bytes so concurrent HEAD/PATCH/DELETE requests across cluster nodes detect the active
          // lock and trigger clean contention resolution, and so lock.close() cleans up the
          // registered stream from activeInputStreams upon completion.
          boolean needsLock =
              uploadLockingService != null
                  && uploadInfo.getId() != null
                  && !uploadLockingService.isLocked(uploadInfo.getId());
          try (UploadLock ignored =
              needsLock ? uploadLockingService.lockUploadByUri(location) : null) {
            InputStream stream = servletRequest.getContentInputStream();
            InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(stream);
            if (uploadLockingService != null) {
              uploadLockingService.registerInputStream(location, interruptibleStream);
            }
            stream = interruptibleStream;

            try {
              uploadInfo = uploadStorageService.append(uploadInfo, stream);
            } catch (IOException e) {
              // When an upload stream is interrupted by the locking service watchdog or a
              // concurrent
              // lock contention release request (e.g. from a concurrent HEAD or DELETE), the
              // storage
              // backend (Disk, S3, Azure) commits all bytes received up to the interruption and
              // updates the offset in storage. We reload the updated UploadInfo and acknowledge the
              // partial upload rather than propagating an unhandled error to the servlet container.
              if (interruptibleStream.isInterrupted()) {
                UploadInfo refreshed = uploadStorageService.getUploadInfo(location, ownerKey);
                if (refreshed != null) {
                  uploadInfo = refreshed;
                }
                log.info(
                    "Upload creation-with-upload POST request for URI {} was interrupted by locking"
                        + " service contention; saved partial upload up to offset {}",
                    location,
                    uploadInfo.getOffset());
              } else {
                throw e;
              }
            } finally {
              if (uploadLockingService != null) {
                uploadLockingService.unregisterInputStream(location);
              }
            }

            servletResponse.setHeader(
                HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
          }
        }
      }
    }
    return null;
  }
}
