package me.desair.tus.server.exception;

import lombok.Getter;

public class TusException extends Exception {

    @Getter
    private final int status;

    public TusException(final int status, final String message) {
        this(status, message, null);
    }

    public TusException(final int status, final String message, Throwable e) {
        super(message, e);
        this.status = status;
    }
}
