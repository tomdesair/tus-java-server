package me.desair.tus.server.creation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Server MUST acknowledge a successful upload creation with the 201 Created status. The Server
 * MUST set the Location header to the URL of the created resource. This URL MAY be absolute or
 * relative.
 */
public class CreationPostRequestHandler extends AbstractRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(CreationPostRequestHandler.class);

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException {

    UploadInfo info = buildUploadInfo(servletRequest);
    info = uploadStorageService.create(info, ownerKey);

    // We've already validated that the current request URL matches our upload URL so we can
    // safely
    // use it.
    String uploadUri = servletRequest.getRequestURI();

    // It's important to return relative UPLOAD URLs in the Location header in order to support
    // HTTPS proxies
    // that sit in front of the web app
    String url = uploadUri + (StringUtils.endsWith(uploadUri, "/") ? "" : "/") + info.getId();
    servletResponse.setHeader(HttpHeader.LOCATION, url);
    servletResponse.setStatus(HttpServletResponse.SC_CREATED);

    log.info(
        "Created upload with ID {} at {} for ip address {} with location {}",
        info.getId(),
        info.getCreationTimestamp(),
        info.getCreatorIpAddresses(),
        url);
  }

  private UploadInfo buildUploadInfo(HttpServletRequest servletRequest) {
    UploadInfo info = new UploadInfo(servletRequest);

    Long length = Utils.getLongHeader(servletRequest, HttpHeader.UPLOAD_LENGTH);
    if (length != null) {
      info.setLength(length);
    }

    String metadata = Utils.getHeader(servletRequest, HttpHeader.UPLOAD_METADATA);
    if (StringUtils.isNotBlank(metadata)) {
      info.setEncodedMetadata(metadata);
    }

    return info;
  }
}
