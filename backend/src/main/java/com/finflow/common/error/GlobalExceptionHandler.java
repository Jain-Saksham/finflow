package com.finflow.common.error;

import com.finflow.common.web.RequestIdFilter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 problem details with two extra fields: a stable {@code code}
 * and the {@code requestId} that also appears in logs. Field-level problems go in {@code errors}.
 * Extending {@link ResponseEntityExceptionHandler} means Spring MVC's own exceptions (bad JSON,
 * missing headers, 405s, ...) get the same shape instead of falling through to a 500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        return build(ex.code(), ex.getMessage(), null);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> handleLocking(RuntimeException ex) {
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently. Retry the request.", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        // Services translate the constraint violations they expect; anything reaching here is a
        // conflicting write we did not anticipate, so keep database details out of the response.
        log.warn("Unmapped data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return build(ErrorCode.DUPLICATE_RESOURCE, "The request conflicts with existing data.", null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", null);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .toList();
        return asObject(build(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error ->
                    errors.add(new FieldViolation(name, error.getDefaultMessage())));
        });
        return asObject(build(ErrorCode.VALIDATION_FAILED, "Request validation failed.", errors));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            decorate(problem, ErrorCode.fromStatus(statusCode.value()));
        }
        return response;
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, @Nullable List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        decorate(problem, code);
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return ResponseEntity.status(code.status()).body(problem);
    }

    private static void decorate(ProblemDetail problem, ErrorCode code) {
        Map<String, Object> existing = problem.getProperties();
        if (existing == null || !existing.containsKey("code")) {
            problem.setProperty("code", code.name());
        }
        problem.setType(URI.create("https://finflow.dev/errors/" + code.name().toLowerCase().replace('_', '-')));
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            problem.setProperty("requestId", requestId);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ResponseEntity<Object> asObject(ResponseEntity<ProblemDetail> response) {
        return (ResponseEntity) response;
    }
}
