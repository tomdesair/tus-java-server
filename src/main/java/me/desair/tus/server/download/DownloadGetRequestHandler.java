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
import me.desair.tus.server.upload.UploadId;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

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

    String sanitizedFileName = sanitizeFileName(info.getFileName(), info.getId());
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

  /**
   * Sanitizes the given filename to prevent directory traversal and header corruption attacks on
   * clients consuming the Content-Disposition header.
   */
  private String sanitizeFileName(String fileName, UploadId uploadId) {
    if (fileName == null || fileName.trim().isEmpty()) {
      return uploadId != null ? uploadId.toString() : "download";
    }
    // Prevent client-side path traversal: strip directory paths across Unix and Windows separators
    String sanitized = fileName.trim();
    int lastSlash = Math.max(sanitized.lastIndexOf('/'), sanitized.lastIndexOf('\\'));
    if (lastSlash >= 0) {
      sanitized = sanitized.substring(lastSlash + 1).trim();
    }
    // Remove directory traversal sequences (..)
    sanitized = sanitized.replace("..", "").trim();
    if (sanitized.isEmpty()) {
      return uploadId != null ? uploadId.toString() : "download";
    }
    return sanitized;
  }
}
