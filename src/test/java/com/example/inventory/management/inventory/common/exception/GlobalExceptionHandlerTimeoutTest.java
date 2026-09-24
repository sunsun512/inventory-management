package com.example.inventory.management.inventory.common.exception;

import com.example.inventory.management.inventory.common.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the exception → 503 mapping for timeout types that are hard to provoke
 * deterministically end-to-end, resolving the handler exactly like Spring MVC does
 * (most specific @ExceptionHandler match) without needing a controller or DB.
 */
class GlobalExceptionHandlerTimeoutTest {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandlerTimeoutTest.class);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    void 락_획득_실패는_503을_반환한다() throws Exception {
        assertServiceUnavailable(new CannotAcquireLockException("canceling statement due to lock timeout"));
    }

    @Test
    void 비관적_락_실패는_503을_반환한다() throws Exception {
        assertServiceUnavailable(new PessimisticLockingFailureException("lock_not_available"));
    }

    @Test
    void 쿼리_실행_시간_초과는_503을_반환한다() throws Exception {
        assertServiceUnavailable(new QueryTimeoutException("canceling statement due to statement timeout"));
    }

    @Test
    void 트랜잭션_시간_초과는_503을_반환한다() throws Exception {
        assertServiceUnavailable(new TransactionTimedOutException("transaction timed out"));
    }

    @SuppressWarnings("unchecked")
    private void assertServiceUnavailable(Exception ex) throws Exception {
        Method method = resolver.resolveMethod(ex);
        ResponseEntity<ErrorResponse> response = (ResponseEntity<ErrorResponse>) method.invoke(handler, ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().code()).isEqualTo("STOCK_LOCK_TIMEOUT");
        log.error("예상된 503 STOCK_LOCK_TIMEOUT 응답 확인: type={}", ex.getClass().getSimpleName());
    }
}
