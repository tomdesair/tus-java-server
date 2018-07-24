package me.desair.tus.server.exception;

import javax.servlet.http.HttpServletResponse;

/**
 * Exception thrown when the Client includes the Upload-Length header in the final upload creation.
 */
public class UploadLengthNotAllowedOnConcatenationException extends TusException {
    public UploadLengthNotAllowedOnConcatenationException(final String message) {
        super(HttpServletResponse.SC_BAD_REQUEST, message);
    }
}
