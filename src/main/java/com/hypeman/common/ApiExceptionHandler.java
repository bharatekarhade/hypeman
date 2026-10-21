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

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handle(ApiException e) {
        return ResponseEntity.status(e.status()).body(ApiError.of(e.code(), e.getMessage()));
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
}
