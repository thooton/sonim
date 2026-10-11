package com.teslacharge.xp3.data;

public final class ApiException extends Exception {
    public final int status;

    public ApiException(String message, int status) {
        super(message);
        this.status = status;
    }

    public ApiException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }
}
