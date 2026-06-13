package me.desair.tus.server.checksum;

import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.checksum.validation.ChecksumAlgorithmValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadChecksumMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.AbstractRequestHandler;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;

public class ChecksumPatchRequestHandler extends AbstractRequestHandler {

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

    String uploadChecksumHeader = servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM);

    if (servletRequest.hasCalculatedChecksum() && StringUtils.isNotBlank(uploadChecksumHeader)) {

      // The Upload-Checksum header can be a trailing header which is only present after
      // reading the
      // full content.
      // Therefor we need to revalidate that header here
      new ChecksumAlgorithmValidator()
          .validate(method, servletRequest, uploadStorageService, ownerKey);

      Utils.ChecksumInfo checksumInfo = Utils.parseUploadChecksumHeader(servletRequest);
      if (checksumInfo != null) {
        String expectedValue = checksumInfo.getValue();
        ChecksumAlgorithm checksumAlgorithm = checksumInfo.getAlgorithm();
        String calculatedValue = servletRequest.getCalculatedChecksum(checksumAlgorithm);

        if (!java.util.Objects.equals(expectedValue, calculatedValue)) {
          // throw an exception if the checksum is invalid. This will also trigger the removal
          // of any
          // bytes that were already saved
          throw new UploadChecksumMismatchException(
              "Expected checksum "
                  + expectedValue
                  + " but was "
                  + calculatedValue
                  + " with algorithm "
                  + checksumAlgorithm);
        } else if (uploadStorageService.isUploadDeduplicationEnabled()) {
          UploadInfo uploadInfo =
              uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);
          if (uploadInfo != null
              && !uploadInfo.isUploadInProgress()
              && uploadInfo.getDuplicatesUploadId() == null) {
            uploadInfo.setChecksum(expectedValue);
            uploadInfo.setChecksumAlgorithm(checksumAlgorithm);
            uploadStorageService.update(uploadInfo);
          }
        }
      }
    }
  }
}
