package me.desair.tus.server.creation.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.util.Utils;
import me.desair.tus.server.core.validation.AbstractRequestValidator;
import me.desair.tus.server.exception.MaxUploadLengthExceededException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadIdFactory;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;

/**
 * If the length of the upload exceeds the maximum, which MAY be specified using the Tus-Max-Size header,
 * the Server MUST respond with the 413 Request Entity Too Large status.
 */
public class UploadLengthValidator extends AbstractRequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService,
                         final UploadIdFactory idFactory) throws TusException {

        Long uploadLength = Utils.getLongHeader(request, HttpHeader.UPLOAD_LENGTH);
        if(uploadLength != null
                && uploadStorageService.getMaxSizeInBytes() > 0
                && uploadLength > uploadStorageService.getMaxSizeInBytes()) {

            throw new MaxUploadLengthExceededException("Upload requests can have a maximum size of "
                    + uploadStorageService.getMaxSizeInBytes());
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
