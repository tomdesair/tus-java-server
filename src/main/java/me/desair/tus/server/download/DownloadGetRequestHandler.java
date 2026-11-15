package me.desair.tus.server.download;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;

/** Send the uploaded bytes of finished uploads. */
public class DownloadGetRequestHandler extends AbstractRequestHandler {

  private static final String CONTENT_DISPOSITION_FORMAT =
      "attachment; filename=\"%s\"; filename*=UTF-8''%s";

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.GET.equals(method);
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
    if (info == null || info.isExpired()) {

      throw new UploadNotFoundException("The requested upload cannot be found or has expired");
    }

    if (info.isUploadInProgress()) {
      // Delegate to RufhHeadGetRequestHandler for RUFH offset retrieval on in-progress uploads
      servletResponse.setStatus(HttpServletResponse.SC_NO_CONTENT);
      servletResponse.setHeader(HttpHeader.CONTENT_LENGTH, "0");
      return;
    }

    servletResponse.setHeader(HttpHeader.CONTENT_LENGTH, Objects.toString(info.getLength()));

    // info.getFileName() is pre-sanitized, but passing through Utils.sanitizeFileName ensures
    // idempotent directory traversal protection before generating Content-Disposition headers
    String sanitizedFileName = Utils.sanitizeFileName(info.getFileName(), info.getId());
    servletResponse.setHeader(
        HttpHeader.CONTENT_DISPOSITION,
        String.format(
            CONTENT_DISPOSITION_FORMAT,
            sanitizedFileName.replace("\"", ""),
            URLEncoder.encode(sanitizedFileName, StandardCharsets.UTF_8.toString())
                .replace("+", "%20")));

    servletResponse.setHeader(HttpHeader.CONTENT_TYPE, info.getFileMimeType());
    // Prevent MIME-sniffing attacks by browsers when serving uploaded user content
    servletResponse.setHeader(HttpHeader.X_CONTENT_TYPE_OPTIONS, "nosniff");
    servletResponse.setStatus(HttpServletResponse.SC_OK);

    uploadStorageService.copyUploadTo(info, servletResponse.getOutputStream());
  }
}
