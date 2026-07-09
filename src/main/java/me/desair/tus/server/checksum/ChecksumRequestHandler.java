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
import org.apache.commons.lang3.Strings;

/** Request handler that verifies the checksum of the uploaded data. */
public class ChecksumRequestHandler extends AbstractRequestHandler {

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method) || HttpMethod.POST.equals(method);
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

      // The Upload-Checksum header can be a trailing header which is only present after reading
      // the full content. Therefore we need to revalidate that header here.
      new ChecksumAlgorithmValidator()
          .validate(method, servletRequest, uploadStorageService, ownerKey);

      Utils.ChecksumInfo checksumInfo = Utils.parseUploadChecksumHeader(servletRequest);
      if (checksumInfo != null) {
        String expectedValue = checksumInfo.getValue();
        ChecksumAlgorithm checksumAlgorithm = checksumInfo.getAlgorithm();
        String calculatedValue = servletRequest.getCalculatedChecksum(checksumAlgorithm);

        if (!Strings.CS.equals(expectedValue, calculatedValue)) {
          // Throw an exception if the checksum is invalid. This will also trigger the removal of
          // any bytes that were already saved.
          throw new UploadChecksumMismatchException(
              "Expected checksum "
                  + expectedValue
                  + " but was "
                  + calculatedValue
                  + " with algorithm "
                  + checksumAlgorithm);
        } else if (uploadStorageService.isUploadDeduplicationEnabled()) {
          UploadInfo uploadInfo =
              uploadStorageService.getUploadInfo(
                  Utils.getUploadURI(servletRequest, servletResponse), ownerKey);
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
