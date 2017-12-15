package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UploadNotFoundException;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

/**
 * If the resource is not found, the Server SHOULD return either the
 * 404 Not Found, 410 Gone or 403 Forbidden status without the Upload-Offset header.
 */
public class IdExistsValidator extends AbstractRequestValidator {

    @Override
    public void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService) throws TusException, IOException {

        if(uploadStorageService.getUploadInfo(request.getRequestURI()) == null) {
            throw new UploadNotFoundException("The upload for path " + request.getRequestURI() + " was not found.");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        if(method != null && (
                HttpMethod.HEAD.equals(method) ||
                HttpMethod.PATCH.equals(method)
                )) {
            return true;
        } else {
            return false;
        }
    }

}
