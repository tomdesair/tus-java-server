package me.desair.tus.server;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;

import me.desair.tus.server.exception.TusException;
import me.desair.tus.server.upload.UploadStorageService;

/**
 * Interface for request validators
 */
public interface RequestValidator {

    /**
     * Validate if the request should be processed
     * @param method The HTTP method of this request (do not use {@link HttpServletRequest#getMethod()}!)
     * @param request The {@link HttpServletRequest} to validate
     * @param uploadStorageService The current upload storage service
     * @param ownerKey
     * @throws TusException When validation fails and the request should not be processed
     */
    void validate(final HttpMethod method, final HttpServletRequest request, final UploadStorageService uploadStorageService, final String ownerKey) throws TusException, IOException;

    /**
     * Test if this validator supports the given HTTP method
     * @param method The current HTTP method
     * @return true if supported, false otherwise
     */
    boolean supports(final HttpMethod method);
}
