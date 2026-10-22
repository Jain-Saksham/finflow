package com.finflow.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes returned in the {@code code} field of every error response. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    UNKNOWN_PAYMENT_METHOD(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT),
    ORDER_HAS_ACTIVE_PAYMENT(HttpStatus.CONFLICT),
    PAYMENT_NOT_REFUNDABLE(HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT),
    IDEMPOTENCY_REQUEST_IN_PROGRESS(HttpStatus.CONFLICT),
    RECONCILIATION_IN_PROGRESS(HttpStatus.CONFLICT),
    LEDGER_JOURNAL_ALREADY_REVERSED(HttpStatus.CONFLICT),
    WEBHOOK_ENDPOINT_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT),
    WEBHOOK_URL_NOT_ALLOWED(HttpStatus.UNPROCESSABLE_CONTENT),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    REFUND_EXCEEDS_REFUNDABLE(HttpStatus.UNPROCESSABLE_CONTENT),
    REFUND_WINDOW_EXPIRED(HttpStatus.UNPROCESSABLE_CONTENT),
    PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
    SIMULATED_CRASH(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONCURRENT_MODIFICATION;
            case 429 -> RATE_LIMITED;
            default -> status >= 500 ? INTERNAL_ERROR : MALFORMED_REQUEST;
        };
    }
}
