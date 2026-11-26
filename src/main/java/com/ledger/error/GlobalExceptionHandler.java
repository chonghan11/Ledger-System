package com.ledger.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/** Maps every failure to an RFC 9457 problem+json body with a stable "code" property. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail handleMethodValidation(HandlerMethodValidationException e) {
        String msg = e.getAllErrors().stream()
                .map(err -> err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", msg);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingHeader(MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "required header '" + e.getHeaderName() + "' is missing");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleUnreadable(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request could not be parsed");
    }

    /**
     * Last line of defence: a DB constraint fired that the service layer did not
     * pre-check (e.g. two concurrent reversals racing on UNIQUE(reverses_id)).
     * The transaction has already rolled back, so no money moved.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleIntegrity(DataIntegrityViolationException e) {
        log.warn("constraint violation", e);
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION", "request conflicts with current ledger state");
    }

    /** e.g. Math.addExact on an absurd balance. Rejected, never wrapped; the transaction rolled back. */
    @ExceptionHandler(ArithmeticException.class)
    ProblemDetail handleArithmetic(ArithmeticException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_OUT_OF_RANGE", "amount is outside the supported range");
    }

    /**
     * Lock timeout or a deadlock detected by Postgres. Ordered locking should prevent deadlocks, but if one
     * ever happens the transaction was rolled back cleanly and the client can retry with the same
     * Idempotency-Key.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail handleLock(PessimisticLockingFailureException e) {
        log.warn("lock failure", e);
        return problem(HttpStatus.CONFLICT, "CONCURRENCY_CONFLICT", "the request conflicted with another; retry with the same Idempotency-Key");
    }

    /** Everything else: Spring MVC's own errors keep their status (404, 405, 415...); unknown errors are 500. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleOther(Exception e) {
        if (e instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            return problem(status, status.name(), er.getBody().getDetail());
        }
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected error; no changes were made");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        return pd;
    }
}
