package me.desair.tus.server.exception;

import jakarta.servlet.http.HttpServletResponse;

/**
 * The Server MUST respond with the 403 Forbidden status to PATCH requests against a upload URL
 */
public class PatchOnFinalUploadNotAllowedException extends TusException {

    public PatchOnFinalUploadNotAllowedException(String message) {
        super(HttpServletResponse.SC_FORBIDDEN, message);
    }
}
