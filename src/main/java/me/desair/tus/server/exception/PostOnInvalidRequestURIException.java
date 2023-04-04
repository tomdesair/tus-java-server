package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when a POST request was received on an invalid URI
 */
public class PostOnInvalidRequestURIException extends TusException {

    public PostOnInvalidRequestURIException(String message) {
        super(HttpServletResponse.SC_METHOD_NOT_ALLOWED, message);
    }
}
