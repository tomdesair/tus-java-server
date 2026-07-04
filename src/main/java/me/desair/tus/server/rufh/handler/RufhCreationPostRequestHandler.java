package me.desair.tus.server.rufh.handler;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.rufh.InterimResponseStrategy;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadLockingService;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.InterruptibleInputStream;
import me.desair.tus.server.util.StructuredHeaderUtil;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;

/**
 * Request handler for upload creation requests via HTTP POST, PUT, or PATCH.
 *
 * <p>Handles upload initialization, optional 104 interim responses, payload byte streaming, lock
 * registration via {@link InterruptibleInputStream}, and response headers.
 *
 * <p>Reference: Section 4.2 (Upload Creation) & Section 4.2.2 (Server Behavior) of
 * draft-ietf-httpbis-resumable-upload-11.
 */
public class RufhCreationPostRequestHandler extends AbstractRequestHandler {

  private final InterimResponseStrategy interimResponseStrategy;

  public RufhCreationPostRequestHandler(InterimResponseStrategy interimResponseStrategy) {
    this.interimResponseStrategy = interimResponseStrategy;
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.POST.equals(method)
        || HttpMethod.PUT.equals(method)
        || HttpMethod.PATCH.equals(method);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws IOException, TusException {
    process(method, servletRequest, servletResponse, uploadStorageService, null, ownerKey);
  }

  @Override
  public void process(
      HttpMethod method,
      TusServletRequest servletRequest,
      TusServletResponse servletResponse,
      UploadStorageService uploadStorageService,
      UploadLockingService uploadLockingService,
      String ownerKey)
      throws IOException, TusException {

    if (HttpMethod.PATCH.equals(method)
        && isExistingUpload(servletRequest, uploadStorageService, ownerKey)) {
      // Existing upload on PATCH request is handled by RufhAppendPatchRequestHandler
      return;
    }

    UploadInfo uploadInfo = new UploadInfo();
    String uploadLengthHeader = servletRequest.getHeader(HttpHeader.UPLOAD_LENGTH);
    Long uploadLength = StructuredHeaderUtil.parseInteger(uploadLengthHeader);
    if (uploadLength != null && uploadLength >= 0) {
      uploadInfo.setLength(uploadLength);
    }

    String uploadCompleteHeader = servletRequest.getHeader(HttpHeader.UPLOAD_COMPLETE);
    Boolean uploadComplete = StructuredHeaderUtil.parseBoolean(uploadCompleteHeader);

    uploadInfo = uploadStorageService.create(uploadInfo, ownerKey);
    String uploadUri = getUploadUri(uploadInfo, servletRequest, uploadStorageService);

    if (interimResponseStrategy != null) {
      interimResponseStrategy.sendInterimResponse(servletResponse, uploadUri, 0L);
    }

    InputStream is = servletRequest.getContentInputStream();
    if (is != null && servletRequest.getContentLengthLong() != 0) {
      if (uploadLockingService != null) {
        InterruptibleInputStream interruptibleStream = new InterruptibleInputStream(is);
        uploadLockingService.registerInputStream(uploadUri, interruptibleStream);
        is = interruptibleStream;
      }
      UploadInfo appended = uploadStorageService.append(uploadInfo, is);
      if (appended != null) {
        uploadInfo = appended;
      }
    }

    servletResponse.setHeader(HttpHeader.UPLOAD_DRAFT, "11");

    boolean isFinished = Boolean.TRUE.equals(uploadComplete) || isUploadCompleted(uploadInfo);
    if (isFinished) {
      uploadInfo.setLength(uploadInfo.getOffset());
      uploadStorageService.update(uploadInfo);
      servletResponse.setStatus(200);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(true));
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));
    } else {
      servletResponse.setStatus(201);
      servletResponse.setHeader(HttpHeader.LOCATION, uploadUri);
      servletResponse.setHeader(
          HttpHeader.UPLOAD_COMPLETE, StructuredHeaderUtil.formatBoolean(false));
      servletResponse.setHeader(HttpHeader.UPLOAD_OFFSET, String.valueOf(uploadInfo.getOffset()));

      addUploadLimitHeader(servletResponse, uploadStorageService);
    }
  }

  private boolean isExistingUpload(
      TusServletRequest request, UploadStorageService uploadStorageService, String ownerKey)
      throws IOException {
    String requestUri = request.getRequestURI();
    return uploadStorageService.getUploadInfo(requestUri, ownerKey) != null;
  }

  private boolean isUploadCompleted(UploadInfo uploadInfo) {
    return !uploadInfo.isUploadInProgress();
  }

  private String getUploadUri(
      UploadInfo uploadInfo,
      TusServletRequest servletRequest,
      UploadStorageService storageService) {
    String baseUri = storageService.getUploadUri();
    if (baseUri == null) {
      baseUri = servletRequest.getRequestURI();
    }
    String idStr = uploadInfo.getId() != null ? uploadInfo.getId().toString() : "";
    return baseUri + (baseUri.endsWith("/") ? "" : "/") + idStr;
  }

  private void addUploadLimitHeader(
      TusServletResponse response, UploadStorageService uploadStorageService) {
    Map<String, Object> limits = new LinkedHashMap<>();
    long maxSize = uploadStorageService.getMaxUploadSize();
    if (maxSize > 0) {
      limits.put("max-size", maxSize);
    }
    Long maxAppendSize = uploadStorageService.getMaxAppendSize();
    if (maxAppendSize != null && maxAppendSize > 0) {
      limits.put("max-append-size", maxAppendSize);
    }
    if (!limits.isEmpty()) {
      response.setHeader(HttpHeader.UPLOAD_LIMIT, StructuredHeaderUtil.formatDictionary(limits));
    }
  }
}
