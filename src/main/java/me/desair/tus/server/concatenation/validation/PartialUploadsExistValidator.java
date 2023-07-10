package me.desair.tus.server.concatenation.validation;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

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
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey)
            throws IOException, TusException {

        String uploadConcatValue = request.getHeader(HttpHeader.UPLOAD_CONCAT);

        if (StringUtils.startsWithIgnoreCase(uploadConcatValue, "final")) {

            for (String uploadUri : Utils.parseConcatenationIDsFromHeader(uploadConcatValue)) {

                UploadInfo uploadInfo = uploadStorageService.getUploadInfo(uploadUri, ownerKey);
                if (uploadInfo == null) {
                    throw new InvalidPartialUploadIdException("The URI " + uploadUri
                            + " in Upload-Concat header does not match an existing upload");
                }
            }
        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }

}
