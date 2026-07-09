package me.desair.tus.server.checksum.validation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;

/**
 * The Server MAY respond with one of the following status code: 400 Bad Request if the checksum
 * algorithm is not supported by the server or if the checksum header is malformed
 */
public class ChecksumAlgorithmValidator implements RequestValidator {

  @Override
  public void validate(
      HttpMethod method,
      HttpServletRequest request,
      UploadStorageService uploadStorageService,
      String ownerKey)
      throws TusException, IOException {

    String uploadChecksum = request.getHeader(HttpHeader.UPLOAD_CHECKSUM);

    if (StringUtils.isNotBlank(uploadChecksum)) {
      // Check that we support the algorithm
      if (ChecksumAlgorithm.forUploadChecksumHeader(uploadChecksum) == null) {
        throw new ChecksumAlgorithmNotSupportedException(
            "The "
                + HttpHeader.UPLOAD_CHECKSUM
                + " header value "
                + uploadChecksum
                + " is not supported");
      }

      // Check that the header is not malformed
      if (Utils.parseUploadChecksumHeader(request) == null) {
        throw new TusException(
            HttpServletResponse.SC_BAD_REQUEST, "The Upload-Checksum header is malformed");
      }
    }
  }

  @Override
  public boolean supports(HttpMethod method) {
    return HttpMethod.PATCH.equals(method) || HttpMethod.POST.equals(method);
  }
}
