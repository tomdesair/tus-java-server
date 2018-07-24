package me.desair.tus.server.exception;

import javax.servlet.http.HttpServletResponse;

/**
 * The Server MUST respond with the 403 Forbidden status to PATCH requests against a final upload URL
 */
public class PatchOnFinalUploadNotAllowedException extends TusException {

    public PatchOnFinalUploadNotAllowedException(final String message) {
        super(HttpServletResponse.SC_FORBIDDEN, message);
    }
}
