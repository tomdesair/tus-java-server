package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the request has an invalid content type.
 */
public class InvalidContentTypeException extends TusException {
    public InvalidContentTypeException(String message) {
        super(HttpServletResponse.SC_NOT_ACCEPTABLE, message);
    }
}
