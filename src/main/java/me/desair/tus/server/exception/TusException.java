package me.desair.tus.server.exception;

/**
 * Super class for exception in the tus protocol
 */
public class TusException extends Exception {

    private final int status;

    public TusException(final int status, final String message) {
        this(status, message, null);
    }

    public TusException(final int status, final String message, final Throwable e) {
        super(message, e);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
