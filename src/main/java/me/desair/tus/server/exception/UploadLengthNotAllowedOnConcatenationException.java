package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the Client includes the Upload-Length header in the upload creation.
 */
public class UploadLengthNotAllowedOnConcatenationException extends TusException {
    public UploadLengthNotAllowedOnConcatenationException(String message) {
        super(HttpServletResponse.SC_BAD_REQUEST, message);
    }
}
