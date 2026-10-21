package com.hypeman.common;

/** An expected application failure, independent of the transport used to report it. */
public class ApplicationException extends RuntimeException {

    private final String code;

    public ApplicationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
