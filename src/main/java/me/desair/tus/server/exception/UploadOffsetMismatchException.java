package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * If the offsets do not match, the Server MUST respond with the
 * 409 Conflict status without modifying the upload resource.
 */
public class UploadOffsetMismatchException extends TusException {
    public UploadOffsetMismatchException(String message) {
        super(HttpServletResponse.SC_CONFLICT, message);
    }
}
