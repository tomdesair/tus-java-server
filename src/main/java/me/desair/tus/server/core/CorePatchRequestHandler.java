package me.desair.tus.server.core;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.HttpProblemDetails;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Server SHOULD accept PATCH requests against any upload URL and apply the bytes contained in
 * the message at the given offset specified by the Upload-Offset header. <br>
 * The Server MUST acknowledge successful PATCH requests with the 204 No Content status. It MUST
 * include the Upload-Offset header containing the new offset. The new offset MUST be the sum of the
 * offset before the PATCH request and the number of bytes received and processed or stored during
 * the current PATCH request.
 */
public class CorePatchRequestHandler extends AbstractRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(CorePatchRequestHandler.class);

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
    process(method, servletRequest, servletResponse, uploadStorageService, null, ownerKey, null);
  }

  @Override
  public HttpProblemDetails process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService lockingService,
      String ownerKey,
      TusException exception)
      throws IOException, TusException {

    boolean found = true;
    UploadInfo uploadInfo =
        uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);

    if (uploadInfo == null) {
      found = false;
    } else if (uploadInfo.isUploadInProgress()) {
      InterruptibleInputStream interruptibleStream = null;
      try {
        InputStream stream = servletRequest.getContentInputStream();

        // If a locking service is provided, wrap the input stream in an InterruptibleInputStream
        // and register it with the locking service to allow for interruption of the upload.
        if (lockingService != null) {
          interruptibleStream = new InterruptibleInputStream(stream);
          lockingService.registerInputStream(servletRequest.getRequestURI(), interruptibleStream);
          stream = interruptibleStream;
        }

        uploadInfo = uploadStorageService.append(uploadInfo, stream);

        if (uploadStorageService.isUploadDeduplicationEnabled()
            && servletRequest.hasCalculatedChecksum()) {
          Utils.ChecksumInfo checksumInfo = Utils.parseUploadChecksumHeader(servletRequest);
          if (checksumInfo != null) {
            UploadInfo duplicateInfo =
                uploadStorageService.getUploadInfoByChecksum(
                    checksumInfo.getValue(), checksumInfo.getAlgorithm());
            if (duplicateInfo != null) {
              uploadInfo.setDuplicatesUploadId(duplicateInfo.getId());
              uploadStorageService.update(uploadInfo);
            }
          }
        }
      } catch (UploadNotFoundException e) {
        found = false;
      } catch (IOException e) {
        // When an upload stream is interrupted by the locking service watchdog or a concurrent
        // lock contention release request (e.g. from a concurrent HEAD or DELETE), the storage
        // backend (Disk, S3, Azure) commits all bytes received up to the interruption and updates
        // the offset in storage. We reload the updated UploadInfo and acknowledge the partial
        // PATCH with 204 No Content and the new Upload-Offset rather than throwing a 500 error.
        if (interruptibleStream != null && interruptibleStream.isInterrupted()) {
          // Refresh UploadInfo first so the log statement and response reflect the true committed
          // byte offset persisted by the storage backend up to the interruption point.
          UploadInfo refreshed =
              uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
          if (refreshed != null) {
            uploadInfo = refreshed;
          }
          log.info(
              "Upload PATCH request for URI {} was interrupted by locking service contention; "
                  + "saved partial upload up to offset {}",
              servletRequest.getRequestURI(),
              uploadInfo != null ? uploadInfo.getOffset() : "unknown");
        } else {
          throw e;
        }
      }
    }

    if (found) {
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, Objects.toString(uploadInfo.getOffset()));
      servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);

      if (!uploadInfo.isUploadInProgress()) {
        log.info(
            "Upload with ID {} at location {} finished successfully",
            uploadInfo.getId(),
            servletRequest.getRequestURI());
      }
    } else {
      log.error(
          "The patch request handler could not find the upload for URL {0}. "
              + "This means something is really wrong the request validators!",
          servletRequest.getRequestURI());
      servletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    return null;
  }
}
