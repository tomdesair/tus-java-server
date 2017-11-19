package me.desair.tus.server.core.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UnsupportedMethodException;
import me.desair.tus.server.upload.UploadStorageService;

import javax.servlet.http.HttpServletRequest;

/**
 * Class to validate if the current HTTP method is valid
 */
public class HttpMethodValidator extends AbstractRequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request, final UploadStorageService uploadStorageService) throws TusException {
        if(method == null) {
            throw new UnsupportedMethodException("The HTTP method " + request.getMethod() + " is not supported");
        }
    }

    @Override
    public boolean supports(final HttpMethod method) {
        return true;
    }

}
