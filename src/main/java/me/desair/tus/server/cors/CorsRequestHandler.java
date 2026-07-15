package me.desair.tus.server.cors;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

public class CorsRequestHandler implements RequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return true;
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey) {

    String origin = servletRequest.getHeader("Origin");
    if (StringUtils.isNotBlank(origin)) {
      servletResponse.setHeader("Access-Control-Allow-Origin", origin);
      servletResponse.setHeader(
          "Access-Control-Expose-Headers",
          "Upload-Offset, Upload-Length, Upload-Metadata, Upload-Expires, Upload-Concat, "
              + "Tus-Resumable, Tus-Version, Tus-Max-Size, Tus-Extension, Tus-Checksum-Algorithm, Location");

      // Check if it's a preflight request
      String accessControlRequestMethod = servletRequest.getHeader("Access-Control-Request-Method");
      if (HttpMethod.OPTIONS.equals(method) && StringUtils.isNotBlank(accessControlRequestMethod)) {
        servletResponse.setHeader(
            "Access-Control-Allow-Methods", "POST, GET, HEAD, PATCH, DELETE, OPTIONS");
        servletResponse.setHeader(
            "Access-Control-Allow-Headers",
            "Origin, X-Requested-With, Content-Type, Upload-Length, Upload-Offset, Upload-Metadata, "
                + "Upload-Expires, Upload-Checksum, Upload-Concat, Upload-Defer-Length, Tus-Resumable, X-HTTP-Method-Override");
        servletResponse.setHeader("Access-Control-Max-Age", "86400");
      }
    }
  }

  @Override
  public boolean isErrorHandler() {
    return true;
  }
}
