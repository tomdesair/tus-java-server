package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when no valid Upload-Length or Upload-Defer-Length header is found
 */
public class InvalidUploadLengthException extends TusException {

    public InvalidUploadLengthException(String message) {
        super(HttpServletResponse.SC_BAD_REQUEST, message);
    }
}
