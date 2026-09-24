package com.example.inventory.management.inventory.common.exception;

import com.example.inventory.management.inventory.common.response.ErrorResponse;
import com.example.inventory.management.inventory.stock.validation.QuantityLimit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.Optional;

/**
 * Maps every exception to the project's {@link ErrorResponse} body ({@code code}, {@code message},
 * {@code timestamp}).
 *
 * <p>Standard Spring MVC exceptions are handled by {@link ResponseEntityExceptionHandler}, so they keep
 * their proper status (400/404/405/415/...) instead of falling into the catch-all 500. Their body is
 * rewritten in {@link #handleExceptionInternal}, with the {@code code} chosen by status:
 * <ul>
 *   <li>400 (unreadable body, type mismatch, missing parameter, validation) → {@code VALIDATION_FAILED}</li>
 *   <li>500 → {@code INTERNAL_ERROR}</li>
 *   <li>any other status → the {@link HttpStatus} name, e.g. {@code NOT_FOUND}, {@code METHOD_NOT_ALLOWED},
 *       {@code UNSUPPORTED_MEDIA_TYPE}</li>
 *   <li>406 {@link HttpMediaTypeNotAcceptableException} → no body (the client accepts no type we can write)</li>
 * </ul>
 * 4xx client errors are logged at WARN, 5xx at ERROR.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String QUANTITY_LIMIT_CONSTRAINT = QuantityLimit.class.getSimpleName();

    /**
     * A request that only violates the per-request quantity limit is a business-rule conflict
     * (409 QUANTITY_LIMIT_EXCEEDED); any other violation is a malformed request and takes
     * precedence as 400 VALIDATION_FAILED.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Optional<FieldError> malformed = ex.getBindingResult().getFieldErrors().stream()
                .filter(error -> !QUANTITY_LIMIT_CONSTRAINT.equals(error.getCode()))
                .findFirst();
        if (malformed.isEmpty() && ex.getBindingResult().getFieldErrorCount() > 0) {
            FieldError limitError = ex.getBindingResult().getFieldErrors().get(0);
            String message = limitError.getField() + ": " + limitError.getDefaultMessage();
            log.warn("요청 수량 한도 초과: {}", message);
            return handleExceptionInternal(ex,
                    ErrorResponse.of(ErrorCode.QUANTITY_LIMIT_EXCEEDED.name(), message),
                    headers, ErrorCode.QUANTITY_LIMIT_EXCEEDED.getStatus(), request);
        }
        String message = malformed
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("요청 값이 유효하지 않습니다.");
        log.warn("요청 검증 실패: {}", message);
        return handleExceptionInternal(ex,
                ErrorResponse.of(ErrorCode.VALIDATION_FAILED.name(), message),
                headers, ErrorCode.VALIDATION_FAILED.getStatus(), request);
    }

    /**
     * Malformed JSON, missing body, a field of the wrong type (e.g. {@code quantity: "abc"},
     * {@code "5"} or {@code 1.9}), or a field the request does not define.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        log.warn("요청 본문 해석 실패: {}", ex.getMessage());
        String message = unknownPropertyOf(ex)
                .map(property -> property + ": 알 수 없는 필드입니다.")
                .orElse("요청 본문을 해석할 수 없습니다. JSON 형식과 필드 타입(quantity·productId는 따옴표 없는 정수)을 확인하세요.");
        return handleExceptionInternal(ex,
                ErrorResponse.of(ErrorCode.VALIDATION_FAILED.name(), message),
                headers, ErrorCode.VALIDATION_FAILED.getStatus(), request);
    }

    private static Optional<String> unknownPropertyOf(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof UnrecognizedPropertyException unrecognized) {
                return Optional.ofNullable(unrecognized.getPropertyName());
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return Optional.empty();
    }

    /**
     * Common exit for every exception handled by {@link ResponseEntityExceptionHandler}. A body that is
     * already an {@link ErrorResponse} (built by the overrides above) is kept; otherwise one is built
     * from the status. The superclass still takes care of committed responses.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            log.warn("응답 가능한 미디어 타입 없음: {}", ex.getMessage());
            ResponseEntity<Object> accepted = super.handleExceptionInternal(ex, null, headers, statusCode, request);
            return accepted == null ? null : new ResponseEntity<>(accepted.getHeaders(), accepted.getStatusCode());
        }
        Object errorBody = body instanceof ErrorResponse ? body : buildFrameworkErrorBody(ex, statusCode);
        return super.handleExceptionInternal(ex, errorBody, headers, statusCode, request);
    }

    private ErrorResponse buildFrameworkErrorBody(Exception ex, HttpStatusCode statusCode) {
        if (statusCode.is5xxServerError()) {
            log.error("요청 처리 중 서버 오류 발생: status={}", statusCode.value(), ex);
        } else {
            log.warn("잘못된 요청: status={}, type={}, message={}",
                    statusCode.value(), ex.getClass().getSimpleName(), ex.getMessage());
        }
        return ErrorResponse.of(codeFor(statusCode), messageFor(ex, statusCode));
    }

    private static String codeFor(HttpStatusCode statusCode) {
        if (statusCode.value() == HttpStatus.BAD_REQUEST.value()) {
            return ErrorCode.VALIDATION_FAILED.name();
        }
        if (statusCode.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return ErrorCode.INTERNAL_ERROR.name();
        }
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status != null ? status.name() : "HTTP_" + statusCode.value();
    }

    private static String messageFor(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof TypeMismatchException typeMismatch) {
            return typeMismatch.getPropertyName() + ": 요청 값의 타입이 올바르지 않습니다.";
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return missing.getParameterName() + ": 필수 요청 파라미터가 없습니다.";
        }
        if (ex instanceof HttpRequestMethodNotSupportedException methodNotSupported) {
            return "지원하지 않는 HTTP 메서드입니다: " + methodNotSupported.getMethod();
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return "지원하지 않는 Content-Type입니다. application/json으로 요청하세요.";
        }
        return switch (statusCode.value()) {
            case 400 -> "요청 값이 유효하지 않습니다.";
            case 404 -> "요청한 리소스를 찾을 수 없습니다.";
            default -> statusCode.is5xxServerError() ? "서버 오류가 발생했습니다." : "요청을 처리할 수 없습니다.";
        };
    }

    @ExceptionHandler(InventoryException.class)
    public ResponseEntity<ErrorResponse> handleInventoryException(InventoryException ex) {
        if (ex.getErrorCode().getStatus().is4xxClientError()) {
            log.warn("비즈니스 예외 발생: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        } else {
            log.error("비즈니스 예외 발생: code={}, message={}", ex.getErrorCode(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getErrorCode().getStatus())
                .body(ErrorResponse.of(ex.getErrorCode().name(), ex.getMessage()));
    }

    /**
     * Postgres lock_timeout (55P03 → CannotAcquireLockException, a PessimisticLockingFailureException)
     * and statement_timeout (57014 → QueryTimeoutException), plus Spring transaction timeouts:
     * the request was not applied (its transaction rolled back) and is safe to retry with the
     * same requestId, so it's a temporary 503 rather than a 500.
     */
    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            QueryTimeoutException.class,
            TransactionTimedOutException.class
    })
    public ResponseEntity<ErrorResponse> handleLockTimeout(Exception ex) {
        log.error("재고 락 대기 또는 쿼리 실행 시간 초과: type={}, message={}", ex.getClass().getSimpleName(), ex.getMessage());
        return ResponseEntity.status(ErrorCode.STOCK_LOCK_TIMEOUT.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ErrorResponse.of(ErrorCode.STOCK_LOCK_TIMEOUT.name(),
                        "재고 처리 요청이 몰려 제한 시간 내에 처리하지 못했습니다. 같은 requestId로 잠시 후 다시 시도하세요."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.error("데이터 무결성 위반 발생", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(), "데이터 처리 중 오류가 발생했습니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("예상치 못한 서버 오류 발생", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR.name(), "서버 오류가 발생했습니다."));
    }
}
