package me.desair.tus.server.creation.validation;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import me.desair.tus.server.util.Utils;

/**
 * If the length of the upload exceeds the maximum, which MAY be specified using the Tus-Max-Size header,
 * the Server MUST respond with the 413 Request Entity Too Large status.
 */
public class UploadLengthValidator implements RequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final String ownerKey) throws TusException {

        Long uploadLength = Utils.getLongHeader(request, HttpHeader.UPLOAD_LENGTH);
        if(uploadLength != null
                && uploadStorageService.getMaxUploadSize() > 0
                && uploadLength > uploadStorageService.getMaxUploadSize()) {

            throw new MaxUploadLengthExceededException("Upload requests can have a maximum size of "
                    + uploadStorageService.getMaxUploadSize());
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
