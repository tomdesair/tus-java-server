package me.desair.tus.server.checksum.validation;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.checksum.ChecksumAlgorithm;
import me.desair.tus.server.exception.ChecksumAlgorithmNotSupportedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

/**
 * The Server MAY respond with one of the following status code: 400 Bad Request
 * if the checksum algorithm is not supported by the server
 */
public class ChecksumAlgorithmValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey)
            throws TusException, IOException {

        String uploadChecksum = request.getHeader(HttpHeader.UPLOAD_CHECKSUM);

        //If the client provided a checksum header, check that we support the algorithm
        if (StringUtils.isNotBlank(uploadChecksum)
                && ChecksumAlgorithm.forUploadChecksumHeader(uploadChecksum) == null) {

            throw new ChecksumAlgorithmNotSupportedException("The " + HttpHeader.UPLOAD_CHECKSUM + " header value "
                    + uploadChecksum + " is not supported");

        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }
}
