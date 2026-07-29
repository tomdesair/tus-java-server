package me.desair.tus.server.creationwithupload;

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
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
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

    Long contentLength = Utils.getLongHeader(servletRequest, HttpHeader.CONTENT_LENGTH);
    if (contentLength != null && contentLength > 0) {
      String location = servletResponse.getHeader(HttpHeader.LOCATION);
      if (location != null) {
        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(location, ownerKey);
        if (uploadInfo != null && uploadInfo.isUploadInProgress()) {
          InputStream stream = servletRequest.getContentInputStream();
          if (uploadLockingService != null) {
            InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(stream);
            uploadLockingService.registerInputStream(location, interruptibleStream);
            stream = interruptibleStream;
          }

          uploadInfo = uploadStorageService.append(uploadInfo, stream);

          servletResponse.setHeader(
              HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
        }
      }
    }
    return null;
  }
}
