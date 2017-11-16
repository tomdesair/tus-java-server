package me.desair.tus.server.validation;

import me.desair.tus.server.HttpMethod;
import me.desair.tus.server.exception.TusException;

import javax.servlet.http.HttpServletRequest;

/**
 * Interface for request validators
 */
public interface RequestValidator {

    /**
     * Validate if the request should be processed
     * @param method The HTTP method of this request (do not use {@link HttpServletRequest#getMethod()}!)
     * @param request The {@link HttpServletRequest} to validate
     * @throws TusException When validation fails and the request should not be processed
     */
    void validate(final HttpMethod method, final HttpServletRequest request) throws TusException;

}
