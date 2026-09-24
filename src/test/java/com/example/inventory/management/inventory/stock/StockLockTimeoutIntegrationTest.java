package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Not @Transactional: the lock holder and the outbound request must be genuinely separate
 * connections/transactions. lock_timeout is shortened so the blocked request fails fast.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.hikari.data-source-properties.options=-c lock_timeout=500ms -c statement_timeout=5s")
class StockLockTimeoutIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockLockTimeoutIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Test
    void 상품_행_락을_다른_트랜잭션이_오래_잡고_있으면_출고는_무한정_대기하지_않고_503을_반환한다() throws Exception {
        String productCode = "LOCK" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        Long productId = productRepository.upsertProductStock(productCode, "락 테스트 상품", 10L).getId();
        String requestId = "lock-" + UUID.randomUUID();
        log.debug("락 타임아웃 테스트 상품 준비 완료: productId={}", productId);

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM product WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, productId);
                lock.executeQuery();
            }
            log.debug("다른 트랜잭션에서 상품 행 락 획득: productId={}", productId);

            long startedAt = System.nanoTime();
            mockMvc.perform(post("/api/v1/stocks/outbound")
                            .contentType("application/json")
                            .content("{\"productId\": " + productId + ", \"quantity\": 1, \"requestId\": \"" + requestId + "\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("STOCK_LOCK_TIMEOUT"));
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            log.error("예상된 503 STOCK_LOCK_TIMEOUT 응답 확인: productId={}, elapsedMillis={}", productId, elapsedMillis);

            holder.rollback();
        }

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
        assertThat(stockHistoryRepository.findByRequestId(requestId)).isEmpty();
    }
}
