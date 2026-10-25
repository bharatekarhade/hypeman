package com.hypeman.common;

import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns every exception a controller throws into an {@link ApiError}. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ApiError> handle(ApplicationException e) {
        return ResponseEntity.status(statusFor(e.code())).body(ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handle(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return validationFailed(message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handle(HttpMessageNotReadableException e) {
        return validationFailed("The request body is missing or isn't valid JSON.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handle(Exception e) {
        // Spring's own errors, such as an unknown path or the wrong method, keep their status.
        if (e instanceof ErrorResponse spring) {
            HttpStatusCode status = spring.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known != null ? known.name() : "HTTP_" + status.value();
            String message = spring.getBody().getDetail() != null ? spring.getBody().getDetail() : code;
            return ResponseEntity.status(status).body(ApiError.of(code, message));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "Something went wrong on our side."));
    }

    private static ResponseEntity<ApiError> validationFailed(String message) {
        return ResponseEntity.unprocessableContent().body(ApiError.of("VALIDATION_FAILED", message));
    }

    /** Maps application error codes to HTTP only at the web boundary. */
    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "INVALID_IDENTITY_TOKEN", "INVALID_REFRESH_TOKEN", "REFRESH_TOKEN_REUSED", "ACCOUNT_DELETED", "DEVICE_SIGNED_OUT" -> HttpStatus.UNAUTHORIZED;
            case "PACK_NOT_PURCHASED", "SEND_CAP_REACHED" -> HttpStatus.PAYMENT_REQUIRED;
            case "PACK_NOT_OWNED", "PURCHASE_ACCOUNT_MISMATCH", "NOT_FRIENDS" -> HttpStatus.FORBIDDEN;
            case "PACK_NOT_FOUND", "CLIP_NOT_FOUND", "INVITE_NOT_FOUND", "USER_NOT_FOUND", "YELL_NOT_FOUND", "DELIVERY_NOT_FOUND" ->
                    HttpStatus.NOT_FOUND;
            case "PACK_NOT_EDITABLE", "PACK_CLIPS_NOT_READY", "UPLOAD_NOT_FOUND", "INVITE_USED", "YELL_REMOVED" -> HttpStatus.CONFLICT;
            case "INVITE_EXPIRED" -> HttpStatus.GONE;
            case "VALIDATION_FAILED", "UPLOAD_TOO_LARGE", "UNSUPPORTED_FILE_TYPE", "APPLE_REAUTH_REQUIRED",
                    "INVALID_APPLE_AUTHORIZATION_CODE", "APPLE_ACCOUNT_MISMATCH", "INVALID_TRANSACTION", "UNKNOWN_PRODUCT", "INVITE_OWN" ->
                    HttpStatus.UNPROCESSABLE_CONTENT;
            case "APPLE_REVOKE_FAILED" -> HttpStatus.BAD_GATEWAY;
            case "MEDIA_UNAVAILABLE", "APPLE_REVOKE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
