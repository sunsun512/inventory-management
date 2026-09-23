package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class StockControllerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockControllerIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void 입고_수량이_0이하면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKU-1", "productName", "상품", "quantity", 0, "requestId", "r-1");
        log.debug("입고 수량 0 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인: requestId=r-1");
    }

    @Test
    void 상품_식별자가_없으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("quantity", 5, "requestId", "r-2");
        log.debug("상품 식별자 누락 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        log.error("예상된 400 응답 확인 - 상품 식별자 누락: requestId=r-2");
    }

    @Test
    void requestId가_없으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productCode", "SKU-1", "productName", "상품", "quantity", 5);
        log.debug("requestId 누락 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        log.error("예상된 400 응답 확인 - requestId 누락");
    }

    @Test
    void 입고_정상_요청시_200과_예상된_응답을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKU-HAPPY", "productName", "상품", "quantity", 10, "requestId", "r-3");
        log.debug("정상 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKU-HAPPY"))
                .andExpect(jsonPath("$.type").value("INBOUND"))
                .andExpect(jsonPath("$.beforeQuantity").value(0))
                .andExpect(jsonPath("$.afterQuantity").value(10));
        log.info("입고 API 정상 응답 확인: requestId=r-3");
    }

    @Test
    void 존재하지_않는_상품을_출고하면_404를_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productId", 999_999, "quantity", 1, "requestId", "r-4");
        log.debug("존재하지 않는 상품 출고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.error("예상된 404 PRODUCT_NOT_FOUND 응답 확인: requestId=r-4");
    }

    @Test
    void 재고가_부족하면_409를_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKU-LOW", "상품", 3L);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 5, "requestId", "r-5");
        log.debug("재고 부족 출고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        log.error("예상된 409 INSUFFICIENT_STOCK 응답 확인: requestId=r-5");
    }

    @Test
    void 출고_정상_요청시_200과_예상된_응답을_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKU-OK", "상품", 10L);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 4, "requestId", "r-6");
        log.debug("정상 출고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OUTBOUND"))
                .andExpect(jsonPath("$.beforeQuantity").value(10))
                .andExpect(jsonPath("$.afterQuantity").value(6));
        log.info("출고 API 정상 응답 확인: requestId=r-6");
    }
}
