package me.desair.tus.server.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.exception.UnsupportedMethodException;

import javax.servlet.http.HttpServletRequest;

/**
 * Class to validate if the current HTTP method is valid
 */
public class HttpMethodValidator implements RequestValidator {

    @Override
    public void validate(HttpMethod method, HttpServletRequest request) throws TusException {
        if(method == null) {
            throw new UnsupportedMethodException("The HTTP method " + request.getMethod() + " is not supported");
        }
    }
}
