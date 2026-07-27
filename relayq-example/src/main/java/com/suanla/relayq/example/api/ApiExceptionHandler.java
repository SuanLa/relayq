package com.suanla.relayq.example.api;

import com.suanla.relayq.core.exception.HandlerNotRegisteredException;
import com.suanla.relayq.core.exception.IllegalTaskStateException;
import com.suanla.relayq.core.exception.TaskNotFoundException;
import com.suanla.relayq.core.support.TraceContext;
import com.suanla.relayq.example.api.dto.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(HandlerNotRegisteredException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerNotRegistered(
            HandlerNotRegisteredException error,
            HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, error.getMessage(), request, List.of());
    }

    @ExceptionHandler({TaskNotFoundException.class, ApiResourceNotFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            RuntimeException error,
            HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, error.getMessage(), request, List.of());
    }

    @ExceptionHandler(IllegalTaskStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalTaskState(
            IllegalTaskStateException error,
            HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, error.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException error,
            HttpServletRequest request) {
        List<String> violations = error.getBindingResult().getAllErrors().stream()
                .map(objectError -> {
                    if (objectError instanceof FieldError fieldError) {
                        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
                    }
                    return objectError.getDefaultMessage();
                })
                .toList();
        return error(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                violations);
    }

    @ExceptionHandler({
            ConstraintViolationException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ApiErrorResponse> handleRequestValidation(
            Exception error,
            HttpServletRequest request) {
        List<String> violations = new ArrayList<>();
        if (error instanceof ConstraintViolationException constraintError) {
            constraintError.getConstraintViolations().forEach(
                    violation -> violations.add(
                            violation.getPropertyPath() + ": " + violation.getMessage()));
        } else {
            violations.add(safeMessage(error));
        }
        return error(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                violations);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception error,
            HttpServletRequest request) {
        log.error("Unhandled API exception: path={}", request.getRequestURI(), error);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                request,
                List.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<String> violations) {
        ApiErrorResponse body = new ApiErrorResponse();
        body.setTimestamp(Instant.now());
        body.setStatus(status.value());
        body.setError(status.getReasonPhrase());
        body.setMessage(message);
        body.setPath(request.getRequestURI());
        body.setTraceId(currentTraceId());
        body.setValidationErrors(List.copyOf(violations));
        return ResponseEntity.status(status).body(body);
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceContext.TRACE_ID_KEY);
        return traceId == null || traceId.isBlank() ? TraceContext.generateTraceId() : traceId;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
