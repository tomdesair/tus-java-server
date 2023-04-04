package me.desair.tus.server.concatenation.validation;

import java.io.IOException;
import jakarta.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.PatchOnFinalUploadNotAllowedException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadInfo;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.upload.UploadType;

/**
 * The Server MUST respond with the 403 Forbidden status to PATCH requests against a upload URL
 * and MUST NOT modify the or its partial uploads.
 */
public class PatchFinalUploadValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request,
                         UploadStorageService uploadStorageService, String ownerKey)
            throws IOException, TusException {

        UploadInfo uploadInfo = uploadStorageService.getUploadInfo(request.getRequestURI(), ownerKey);

        if (uploadInfo != null && UploadType.CONCATENATED.equals(uploadInfo.getUploadType())) {
            throw new PatchOnFinalUploadNotAllowedException("You cannot send a PATCH request for a "
                    + "concatenated upload URI");
        }
    }

    @Override
    public boolean supports(HttpMethod method) {
        return HttpMethod.PATCH.equals(method);
    }
}
