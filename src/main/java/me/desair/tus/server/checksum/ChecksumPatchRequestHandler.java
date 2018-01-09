package me.desair.tus.server.checksum;

import static me.desair.tus.server.checksum.ChecksumAlgorithm.CHECKSUM_VALUE_SEPARATOR;

import java.io.IOException;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestHandler;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadChecksumMismatchException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.TusServletRequest;
import me.desair.tus.server.util.TusServletResponse;
import org.apache.commons.lang3.StringUtils;

public class ChecksumPatchRequestHandler implements RequestHandler {

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }

    @Override
    public void process(final HttpMethod method, final TusServletRequest servletRequest, final TusServletResponse servletResponse,
                        final UploadStorageService uploadStorageService, final String ownerKey) throws IOException, TusException {
        String uploadChecksumHeader = servletRequest.getHeader(HttpHeader.UPLOAD_CHECKSUM);

        if(servletRequest.hasChecksum() && StringUtils.isNotBlank(uploadChecksumHeader)) {
            String value = StringUtils.substringBefore(uploadChecksumHeader, CHECKSUM_VALUE_SEPARATOR);

            String checksum = servletRequest.getChecksum();
            if(!StringUtils.equals(value, checksum)) {
                //Remove the bytes we've read and written
                UploadInfo uploadInfo = uploadStorageService.getUploadInfo(servletRequest.getRequestURI(), ownerKey);

                uploadStorageService.removeLastNumberOfBytes(uploadInfo, servletRequest.getBytesRead());

                throw new UploadChecksumMismatchException("Expected checksum " + value
                        + " but was " + checksum
                        + " with algorithm " + ChecksumAlgorithm.forUploadChecksumHeader(uploadChecksumHeader));
            }
        }
    }
}
