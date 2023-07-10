package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when we receive a HTTP request with a method name that we do not support
 */
public class UnsupportedMethodException extends TusException {
    public UnsupportedMethodException(String message) {
        super(HttpServletResponse.SC_METHOD_NOT_ALLOWED, message);
    }
}
