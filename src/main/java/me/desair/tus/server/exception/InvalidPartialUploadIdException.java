package me.desair.tus.server.exception;

import javax.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the Upload-Concat header contains an ID which is not valid
 */
public class InvalidPartialUploadIdException extends TusException {
    public InvalidPartialUploadIdException(final String message) {
        super(HttpServletResponse.SC_PRECONDITION_FAILED, message);
    }
}
