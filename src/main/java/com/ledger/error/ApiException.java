package com.ledger.error;

import org.springframework.http.HttpStatus;

/**
 * Base class for every business error. Each carries an HTTP status and a stable,
 * machine-readable code (e.g. INSUFFICIENT_FUNDS) so clients never parse messages.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    // ---- factory methods: one place that defines the error catalogue ----

    public static ApiException notFound(String what, Object id) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " " + id + " not found");
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** Request is well-formed but violates a business rule. */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException insufficientFunds(Object accountId, long available, long required) {
        return unprocessable("INSUFFICIENT_FUNDS",
                "account " + accountId + " has available balance " + available + " but " + required + " is required");
    }
}
