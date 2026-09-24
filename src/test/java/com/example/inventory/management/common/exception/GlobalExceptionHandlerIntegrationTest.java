package com.example.inventory.management.common.exception;

import com.example.inventory.management.product.query.ProductQueryService;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins how Spring MVC framework exceptions (unreadable body, type mismatch, unknown path, wrong
 * method, unsupported/unacceptable media type) are mapped: each keeps its
 * proper 4xx status and the project's ErrorResponse body instead of falling into the catch-all 500.
 * Not @Transactional so every request goes through the real dispatch/exception-resolution path.
 */
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandlerIntegrationTest.class);

    private static final long UNEXPECTED_FAILURE_PRODUCT_ID = 987_654_321L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductQueryService productQueryService;

    private Long productId;

    private String productCode;

    @BeforeEach
    void setUp() {
        productCode = uniqueCode("GEH");
        productId = insertProduct(jdbcTemplate, productCode, "예외 처리 테스트 상품", 10L);
        log.debug("예외 처리 테스트 상품 준비 완료: productId={}", productId);
    }

    @Test
    void 깨진_JSON_본문은_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"quantity\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        log.warn("예상된 400 응답 확인: 깨진 JSON");
    }

    @Test
    void 본문이_없으면_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.warn("예상된 400 응답 확인: 본문 없음");
    }

    @Test
    void 본문_필드_타입이_다르면_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": " + productId + ", \"productCode\": \"" + productCode
                                + "\", \"quantity\": \"abc\", \"requestId\": \"" + newRequestId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.warn("예상된 400 응답 확인: quantity 타입 불일치");
    }

    @Test
    void 정수_범위를_넘는_수량은_400_VALIDATION_FAILED를_반환하고_수량은_변하지_않는다() throws Exception {
        for (String endpoint : new String[]{"inbound", "outbound"}) {
            mockMvc.perform(post("/api/v1/stocks/" + endpoint)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": " + productId + ", \"productCode\": \"" + productCode
                                    + "\", \"quantity\": 99999999999999999999, \"requestId\": \"" + newRequestId() + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mockMvc.perform(get("/api/v1/products/{id}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(10));
        log.warn("예상된 400 응답 확인: Long 범위를 넘는 quantity");
    }

    @Test
    void 정수_범위를_넘는_productId_경로_변수는_400_VALIDATION_FAILED를_반환한다() throws Exception {
        String overflowId = "99999999999999999999";
        mockMvc.perform(get("/api/v1/products/{id}/stock", overflowId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", overflowId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.warn("예상된 400 응답 확인: Long 범위를 넘는 productId 경로 변수");
    }

    @Test
    void 경로_변수_타입이_다르면_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products/abc/stock"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.warn("예상된 400 응답 확인: productId=abc");
    }

    @Test
    void 페이지_크기는_최대_100으로_제한된다() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
        log.info("페이지 크기 상한 확인: productId={}", productId);
    }

    @Test
    void 존재하지_않는_경로는_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        log.warn("예상된 404 응답 확인: 존재하지 않는 경로");
    }

    @Test
    void 지원하지_않는_HTTP_메서드는_405를_반환한다() throws Exception {
        mockMvc.perform(delete("/api/v1/stocks/inbound"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        log.warn("예상된 405 응답 확인: DELETE /api/v1/stocks/inbound");
    }

    @Test
    void 지원하지_않는_Content_Type은_415를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        log.warn("예상된 415 응답 확인: text/plain");
    }

    @Test
    void Content_Type이_없으면_415를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .content("{\"productId\": 1, \"productCode\": \"SKU1\", \"quantity\": 1, \"requestId\": \"" + newRequestId() + "\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        log.warn("예상된 415 응답 확인: Content-Type 없음");
    }

    @Test
    void 응답할_수_없는_Accept는_본문_없이_406을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}/stock", productId).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
        log.warn("예상된 406 응답 확인: Accept=application/xml");
    }

    @Test
    void 검증_실패는_기존_형식대로_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": " + productId + ", \"productCode\": \"" + productCode
                                + "\", \"quantity\": 0, \"requestId\": \"" + newRequestId() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("quantity: ")))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        log.warn("예상된 400 응답 확인: quantity=0");
    }

    @Test
    void 존재하지_않는_상품은_기존대로_404_PRODUCT_NOT_FOUND를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}/stock", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.warn("예상된 404 PRODUCT_NOT_FOUND 응답 확인");
    }

    @Test
    void 예상치_못한_예외는_500_INTERNAL_ERROR를_반환한다() throws Exception {
        doThrow(new IllegalStateException("의도된 테스트 예외"))
                .when(productQueryService).getStock(UNEXPECTED_FAILURE_PRODUCT_ID);

        mockMvc.perform(get("/api/v1/products/{id}/stock", UNEXPECTED_FAILURE_PRODUCT_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
        log.error("예상된 500 INTERNAL_ERROR 응답 확인");
    }
}
