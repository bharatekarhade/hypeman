package com.hypeman.common;

import org.slf4j.MDC;

/** The one JSON shape every failure takes, e.g. {"code": "PACK_NOT_PURCHASED","message": "...", "requestId": "..."}. */
public record ApiError(String code, String message, String requestId) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, MDC.get(RequestIdFilter.MDC_KEY));
    }
}
