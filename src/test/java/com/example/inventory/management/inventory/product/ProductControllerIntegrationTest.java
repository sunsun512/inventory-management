package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.stock.StockHistory;
import com.example.inventory.management.inventory.stock.StockHistoryRepository;
import com.example.inventory.management.inventory.stock.StockType;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ProductControllerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 재고_조회_정상_요청시_200을_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUQ", "상품 Q", 42L);
        log.debug("재고 조회 테스트 상품 준비 완료: productId={}", created.getId());

        mockMvc.perform(get("/api/v1/products/{id}/stock", created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKUQ"))
                .andExpect(jsonPath("$.productName").value("상품 Q"))
                .andExpect(jsonPath("$.quantity").value(42));
        log.info("재고 조회 API 정상 응답 확인: productId={}", created.getId());
    }

    @Test
    void 존재하지_않는_상품의_재고를_조회하면_404를_반환한다() throws Exception {
        log.debug("존재하지 않는 상품 재고 조회 요청 전송: productId=999999");
        mockMvc.perform(get("/api/v1/products/{id}/stock", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.error("예상된 404 PRODUCT_NOT_FOUND 응답 확인: productId=999999");
    }

    @Test
    void 재고_이력_조회시_최신순으로_페이징된_결과를_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUH", "상품 H", 100L);
        log.debug("재고 이력 조회 테스트 상품 준비 완료: productId={}", created.getId());
        for (int i = 0; i < 3; i++) {
            stockHistoryRepository.save(new StockHistory(
                    created.getId(), StockType.INBOUND, 10L, 100L + i * 10, 110L + i * 10, "hist-" + i));
            stockHistoryRepository.flush();
            Thread.sleep(2);
        }

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", created.getId())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].requestId").value("hist-2"));
        log.info("재고 이력 페이징 조회 결과 확인: productId={}, totalElements=3", created.getId());
    }

    @Test
    void 존재하지_않는_상품의_이력을_조회하면_404를_반환한다() throws Exception {
        log.debug("존재하지 않는 상품 이력 조회 요청 전송: productId=999999");
        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", 999_999))
                .andExpect(status().isNotFound());
        log.error("예상된 404 응답 확인 - 존재하지 않는 상품 이력 조회: productId=999999");
    }
    @Test
    void 재고_이력은_생성시각이_아닌_id_역순으로_정렬된다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUORDER", "상품 O", 100L);
        log.debug("이력 정렬 테스트 상품 준비 완료: productId={}", created.getId());
        // created_at is deliberately reversed relative to insertion (id) order: timestamps
        // come from app servers and can disagree with the real apply order, ids cannot.
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("""
                            INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
                            VALUES (?, 'INBOUND', 10, ?, ?, ?, ?)
                            """,
                    created.getId(), 100L + i * 10, 110L + i * 10, "order-" + i,
                    Timestamp.from(base.minusSeconds(i * 60L)));
        }

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", created.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].requestId").value("order-2"))
                .andExpect(jsonPath("$.content[1].requestId").value("order-1"))
                .andExpect(jsonPath("$.content[2].requestId").value("order-0"));
        log.info("재고 이력 id 역순 정렬 확인: productId={}", created.getId());
    }
}
