package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the given upload length exceeds or internally defined maximum
 */
public class MaxUploadLengthExceededException extends TusException {
    public MaxUploadLengthExceededException(String message) {
        super(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, message);
    }
}
