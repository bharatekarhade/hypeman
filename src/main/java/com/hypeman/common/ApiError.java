package com.hypeman.common;

import org.slf4j.MDC;

/** The one JSON shape every failure takes, e.g. {"code": "SEND_CAP_REACHED", "message": "...", "requestId": "..."}. */
public record ApiError(String code, String message, String requestId) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
