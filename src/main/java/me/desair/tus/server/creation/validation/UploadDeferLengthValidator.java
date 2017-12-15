package me.desair.tus.server.creation.validation;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.util.Utils;
import me.desair.tus.server.exception.InvalidUploadLengthException;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.math.NumberUtils;

import javax.servlet.http.HttpServletRequest;

/**
 * The request MUST include one of the following headers:
 * a) Upload-Length to indicate the size of an entire upload in bytes.
 * b) Upload-Defer-Length: 1 if upload size is not known at the time.
 */
public class UploadDeferLengthValidator implements RequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService) throws TusException {
        boolean valid = false;

        if(NumberUtils.isCreatable(Utils.getHeader(request, HttpHeader.UPLOAD_LENGTH))) {
            valid = true;
        }

        if(Utils.getHeader(request, HttpHeader.UPLOAD_DEFER_LENGTH).equals("1")) {
            valid = true;
        }

        if(!valid) {
            throw new InvalidUploadLengthException("No valid value was found in headers " + HttpHeader.UPLOAD_LENGTH
                + " and " + HttpHeader.UPLOAD_DEFER_LENGTH);
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
