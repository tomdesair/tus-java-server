package me.desair.tus.server.download;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.ProtocolVersion;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/** Request handler to add the Tus-specific Upload-Metadata header for download requests. */
public class DownloadUploadMetadataHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.GET.equals(method);
  }

  @Override
  public boolean supports(HttpMethod method, ProtocolVersion version) {
    return HttpMethod.GET.equals(method) && version == ProtocolVersion.TUS_1_0_0;
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {

    UploadInfo info = uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
    if (info != null && !info.isUploadInProgress() && info.hasMetadata()) {
      servletResponse.setHeader(HttpHeader.UPLOAD_METADATA, info.getEncodedMetadata());
    }
  }
}
