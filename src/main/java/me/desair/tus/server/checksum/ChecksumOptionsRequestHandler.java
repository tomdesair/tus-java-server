package me.desair.tus.server.checksum;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractExtensionRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

/**
 * The Tus-Checksum-Algorithm header MUST be included in the response to an OPTIONS request. The
 * Tus-Checksum-Algorithm response header MUST be a comma-separated list of the checksum algorithms
 * supported by the server.
 */
public class ChecksumOptionsRequestHandler extends AbstractExtensionRequestHandler {

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey) {

    super.process(method, servletRequest, servletResponse, uploadStorageService, ownerKey);

    servletResponse.setHeader(
        HttpHeader.TUS_CHECKSUM_ALGORITHM, StringUtils.join(ChecksumAlgorithm.values(), ","));
  }

  @Override
  protected void appendExtensions(StringBuilder extensionBuilder) {
    addExtension(extensionBuilder, "checksum");
    addExtension(extensionBuilder, "checksum-trailer");
  }
}
