package com.example.inventory.management.stock.query;

import com.example.inventory.management.common.exception.ProductNotFoundException;
import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.stock.query.dto.StockHistoryResponse;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class StockHistoryQueryServiceTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockHistoryQueryServiceTest.class);

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private StockHistoryQueryService stockHistoryQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 재고_이력_조회는_최신순_페이지와_다음_페이지_여부를_반환한다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("SKUHIST"), "이력 상품", 0L);
        String first = insertHistory(productId, BASE.minusSeconds(2));
        String second = insertHistory(productId, BASE.minusSeconds(1));
        String third = insertHistory(productId, BASE);

        PageResponse<StockHistoryResponse> page = stockHistoryQueryService.getHistory(productId, 0, 2);

        assertThat(page.content()).extracting(StockHistoryResponse::requestId).containsExactly(third, second);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.hasNext()).isTrue();
        log.info("재고 이력 조회 결과 확인: productId={}, first={}", productId, first);
    }

    @Test
    void 재고_이력_조회_페이지_크기는_최대_100으로_제한된다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("SKUHISTMAX"), "이력 상품", 0L);

        PageResponse<StockHistoryResponse> page = stockHistoryQueryService.getHistory(productId, 0, 1000);

        assertThat(page.size()).isEqualTo(100);
        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void 존재하지_않는_상품의_재고_이력을_조회하면_상품없음_예외가_발생한다() {
        assertThatThrownBy(() -> stockHistoryQueryService.getHistory(999_999L, 0, 10))
                .isInstanceOf(ProductNotFoundException.class);
        log.error("예상된 ProductNotFoundException 발생 확인 - 이력 조회: productId=999999");
    }

    private String insertHistory(Long productId, Instant createdAt) {
        String requestId = newRequestId();
        jdbcTemplate.update("""
                        INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
                        VALUES (?, 'INBOUND', 1, 0, 1, ?, ?)
                        """,
                productId, requestId, Timestamp.from(createdAt));
        return requestId;
    }
}
