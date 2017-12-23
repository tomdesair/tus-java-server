package me.desair.tus.server.exception;

import javax.servlet.http.HttpServletResponse;

public class InvalidUploadOffsetException extends TusException {
    public InvalidUploadOffsetException(final String message) {
        super(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, message);
    }
}
