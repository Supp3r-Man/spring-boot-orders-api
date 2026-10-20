package com.github.sup3rmar10.orders.web;

import com.github.sup3rmar10.orders.error.ApiException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Every error leaves the API as an RFC 9457 application/problem+json document. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
        return problem(ex, ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    /** Includes optimistic-lock losers (two writers raced for the same row). The client may retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<Object> handleConcurrency(ConcurrencyFailureException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, "concurrent-update",
                "The resource was modified concurrently; retry the request", request);
    }

    /** Backstop for unique-constraint races (duplicate SKU, same Idempotency-Key in flight twice). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        return problem(ex, HttpStatus.CONFLICT, "conflict",
                "The request conflicts with concurrent state; retry the request", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return problem(ex, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected server error", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                errors.add(Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()))));
        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(Map.of("field", ge.getObjectName(), "message", String.valueOf(ge.getDefaultMessage()))));
        errors.sort((a, b) -> a.get("field").compareTo(b.get("field")));
        return validationProblem(ex, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors body) {
                // @Valid @RequestBody: report real field paths such as lines[0].quantity
                body.getFieldErrors().forEach(fe ->
                        errors.add(Map.of("field", fe.getField(), "message", String.valueOf(fe.getDefaultMessage()))));
            } else {
                result.getResolvableErrors().forEach(error ->
                        errors.add(Map.of("field", String.valueOf(result.getMethodParameter().getParameterName()),
                                "message", String.valueOf(error.getDefaultMessage()))));
            }
        });
        errors.sort((a, b) -> a.get("field").compareTo(b.get("field")));
        return validationProblem(ex, errors, headers, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, List<Map<String, String>> errors,
            HttpHeaders headers, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle("Validation failed");
        pd.setType(URI.create("urn:problem:orders:validation"));
        pd.setProperty("code", "validation");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> problem(Exception ex, HttpStatus status, String code, String detail,
            WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setType(URI.create("urn:problem:orders:" + code));
        pd.setProperty("code", code);
        return handleExceptionInternal(ex, pd, new HttpHeaders(), status, request);
    }
}
