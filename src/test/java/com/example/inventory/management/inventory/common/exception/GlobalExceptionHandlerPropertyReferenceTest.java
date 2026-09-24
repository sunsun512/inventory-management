package com.example.inventory.management.inventory.common.exception;

import com.example.inventory.management.inventory.common.response.ErrorResponse;
import com.example.inventory.management.inventory.stock.StockHistory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stock-histories sort whitelist rejects unknown properties before the query runs, so a
 * PropertyReferenceException can no longer be provoked end-to-end there. This pins the safety
 * net for any other Pageable/Sort endpoint: an unknown property is a client error (400), not a 500.
 */
class GlobalExceptionHandlerPropertyReferenceTest {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandlerPropertyReferenceTest.class);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    @Test
    @SuppressWarnings("unchecked")
    void 존재하지_않는_속성_참조는_400_VALIDATION_FAILED를_반환한다() throws Exception {
        PropertyReferenceException ex =
                new PropertyReferenceException("nope", TypeInformation.of(StockHistory.class), List.of());

        Method method = resolver.resolveMethod(ex);
        ResponseEntity<ErrorResponse> response = (ResponseEntity<ErrorResponse>) method.invoke(handler, ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_FAILED");
        log.warn("예상된 400 VALIDATION_FAILED 응답 확인: property=nope");
    }
}
