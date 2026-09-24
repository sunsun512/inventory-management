package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

import static com.example.inventory.management.inventory.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.inventory.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.inventory.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Duplicate requestId policy end-to-end: a requestId belongs to the first request that commits
 * successfully; every later request with it gets 409 DUPLICATE_REQUEST (no original result), and
 * failed requests store nothing. Not @Transactional so each request really commits.
 */
@AutoConfigureMockMvc
class StockDuplicateRequestIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockDuplicateRequestIntegrationTest.class);

    private static final String INBOUND = "/api/v1/stocks/inbound";
    private static final String OUTBOUND = "/api/v1/stocks/outbound";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 성공한_출고와_같은_requestId로_다시_요청하면_409_중복_요청을_반환하고_재고는_한_번만_차감된다() throws Exception {
        String code = uniqueCode("DUPSEQ");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        String requestId = newRequestId();
        Map<String, Object> body = outbound(productId, code, 10, requestId);

        postJson(OUTBOUND, body).andExpect(status().isOk());
        log.debug("첫 출고 성공: productId={}, requestId={}", productId, requestId);

        // Stock is now 0, so a re-run would be INSUFFICIENT_STOCK: the duplicate check must win.
        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.afterQuantity").doesNotExist())
                .andExpect(jsonPath("$.productId").doesNotExist());
        log.error("예상된 409 DUPLICATE_REQUEST 응답 확인(순차 재요청): requestId={}", requestId);

        assertThat(quantityOf(productId)).isZero();
        assertThat(historyCount(productId)).isEqualTo(1);
    }

    @Test
    void 성공한_입고와_같은_requestId로_다시_요청하면_409_중복_요청을_반환한다() throws Exception {
        String code = uniqueCode("DUPIN");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productId", productId, "productCode", code, "quantity", 5, "requestId", requestId);

        postJson(INBOUND, body).andExpect(status().isOk());
        postJson(INBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        log.error("예상된 409 DUPLICATE_REQUEST 응답 확인(입고 재요청): requestId={}", requestId);

        assertThat(quantityOf(productId)).isEqualTo(15L);
        assertThat(historyCount(productId)).isEqualTo(1);
    }

    @Test
    void 신규_상품_등록과_같은_requestId로_다시_요청하면_상품코드_중복이_아닌_409_중복_요청을_반환한다() throws Exception {
        String code = uniqueCode("DUPREG");
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productCode", code, "productName", "신규 상품", "quantity", 4, "requestId", requestId);

        postJson(INBOUND, body).andExpect(status().isOk());
        postJson(INBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        log.error("예상된 409 DUPLICATE_REQUEST 응답 확인(신규 등록 재요청): requestId={}", requestId);

        assertThat(productRepository.findByProductCode(code).orElseThrow().getQuantity()).isEqualTo(4L);
    }

    @Test
    void 재고_부족으로_실패한_requestId는_저장되지_않아_재입고_후_같은_requestId로_성공한다() throws Exception {
        String code = uniqueCode("RETRY");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 3L);
        String requestId = newRequestId();
        Map<String, Object> body = outbound(productId, code, 5, requestId);

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        log.error("첫 출고 재고 부족 실패 확인: requestId={}", requestId);
        assertThat(stockHistoryRepository.existsByRequestId(requestId)).isFalse();

        postJson(INBOUND, Map.of("productId", productId, "productCode", code, "quantity", 10, "requestId", newRequestId()))
                .andExpect(status().isOk());

        postJson(OUTBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beforeQuantity").value(13))
                .andExpect(jsonPath("$.afterQuantity").value(8))
                .andExpect(jsonPath("$.requestId").value(requestId));
        log.info("재입고 후 같은 requestId 출고 성공 확인: requestId={}", requestId);

        assertThat(quantityOf(productId)).isEqualTo(8L);
    }

    @Test
    void 같은_requestId로_내용이_다른_요청을_보내도_409_중복_요청을_반환한다() throws Exception {
        String code = uniqueCode("DUPDIFF");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 100L);
        String requestId = newRequestId();

        postJson(OUTBOUND, outbound(productId, code, 1, requestId)).andExpect(status().isOk());

        postJson(OUTBOUND, outbound(productId, code, 7, requestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        postJson(INBOUND, Map.of("productId", productId, "productCode", code, "quantity", 3, "requestId", requestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // Even a payload that would otherwise be 404 is a duplicate first.
        postJson(OUTBOUND, outbound(999_999_999L, "SKUNONE", 1, requestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        log.error("예상된 409 DUPLICATE_REQUEST 응답 확인(내용이 다른 재요청): requestId={}", requestId);

        assertThat(quantityOf(productId)).isEqualTo(99L);
        assertThat(historyCount(productId)).isEqualTo(1);
    }

    @Test
    void 대소문자만_다른_requestId는_같은_키로_취급되어_409_중복_요청을_반환한다() throws Exception {
        String code = uniqueCode("DUPCASE");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 100L);
        String requestId = newRequestId();

        postJson(OUTBOUND, outbound(productId, code, 1, requestId.toUpperCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId));
        postJson(OUTBOUND, outbound(productId, code, 1, requestId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        log.error("예상된 409 DUPLICATE_REQUEST 응답 확인(대소문자만 다른 requestId): requestId={}", requestId);

        assertThat(stockHistoryRepository.existsByRequestId(requestId)).isTrue();
        assertThat(quantityOf(productId)).isEqualTo(99L);
    }

    @Test
    void 요청_검증_오류는_중복_requestId보다_먼저_400으로_응답한다() throws Exception {
        String code = uniqueCode("DUPVAL");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 100L);
        String requestId = newRequestId();

        postJson(OUTBOUND, outbound(productId, code, 1, requestId)).andExpect(status().isOk());

        postJson(OUTBOUND, outbound(productId, code, 0, requestId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인(검증 오류가 중복보다 우선): requestId={}", requestId);
    }

    private static Map<String, Object> outbound(Long productId, String code, int quantity, String requestId) {
        return Map.of("productId", productId, "productCode", code, "quantity", quantity, "requestId", requestId);
    }

    private long quantityOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getQuantity();
    }

    private long historyCount(Long productId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_history WHERE product_id = ?", Long.class, productId);
        return count == null ? 0 : count;
    }

    private ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path).contentType("application/json").content(objectMapper.writeValueAsString(body)));
    }
}
