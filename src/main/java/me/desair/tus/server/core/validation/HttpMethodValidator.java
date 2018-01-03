package me.desair.tus.server.core.validation;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.RequestValidator;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UnsupportedMethodException;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * Class to validate if the current HTTP method is valid
 */
public class HttpMethodValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request, final UploadStorageService uploadStorageService, final String ownerKey) throws TusException {
        if(method == null) {
            throw new UnsupportedMethodException("The HTTP method " + request.getMethod() + " is not supported");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return true;
    }

}
