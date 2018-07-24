package me.desair.tus.server.concatenation.validation;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpHeader;
import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadLengthNotAllowedOnConcatenationException;
import me.desair.tus.server.upload.UploadStorageService;
import org.apache.commons.lang3.StringUtils;

/**
 * The Client MUST NOT include the Upload-Length header in the final upload creation.
 */
public class NoUploadLengthOnFinalValidator implements RequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request,
                         final UploadStorageService uploadStorageService, final String ownerKey)
            throws IOException, TusException {

        String uploadConcatValue = request.getHeader(HttpHeader.UPLOAD_CONCAT);

        if (StringUtils.startsWithIgnoreCase(uploadConcatValue, "final")
                && StringUtils.isNotBlank(request.getHeader(HttpHeader.UPLOAD_LENGTH))) {

            throw new UploadLengthNotAllowedOnConcatenationException(
                    "The upload length of a final concatenated upload cannot be set");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return HttpMethod.POST.equals(method);
    }
}
