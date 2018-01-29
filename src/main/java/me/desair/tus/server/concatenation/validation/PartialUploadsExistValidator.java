package me.desair.tus.server.concatenation.validation;

import java.io.IOException;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.InvalidPartialUploadIdException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;
import org.apache.commons.lang3.StringUtils;

/**
 * Validate that the IDs specified in the Upload-Concat header map to an existing upload
 */
public class PartialUploadsExistValidator implements RequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService,
                         final String ownerKey) throws IOException, TusException {
        String uploadConcatValue = request.getHeader(HttpHeader.UPLOAD_CONCAT);

        if (StringUtils.startsWithIgnoreCase(uploadConcatValue, "final")) {

            for (UUID id : Utils.parseConcatenationIDsFromHeader(uploadConcatValue)) {
                if (id == null) {
                    throw new InvalidPartialUploadIdException("The Upload-Concat header contains an ID which is not a UUID");
                }

                UploadInfo uploadInfo = uploadStorageService.getUploadInfo(id);
                if (uploadInfo == null) {
                    throw new InvalidPartialUploadIdException("The ID " + id
                            + " in Upload-Concat header does not match an existing upload");
                }
            }

        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

}
