package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
                "productCode", "SKU1", "productName", "상품", "quantity", 0, "requestId", "r-1");
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
        Map<String, Object> body = Map.of("productCode", "SKU1", "productName", "상품", "quantity", 5);
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
                "productCode", "SKUHAPPY", "productName", "상품", "quantity", 10, "requestId", "r-3");
        log.debug("정상 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKUHAPPY"))
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
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKULOW", "상품", 3L);
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
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUOK", "상품", 10L);
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
    @Test
    void 소수_수량으로_출고하면_잘라내지_않고_400을_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUDEC", "상품", 10L);
        String body = "{\"productId\": " + created.getId() + ", \"quantity\": 1.9, \"requestId\": \"r-dec-1\"}";
        log.debug("소수 수량 출고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 소수 수량: requestId=r-dec-1");
    }

    @Test
    void 소수_수량으로_입고하면_400을_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUDEC2", "상품", 10L);
        String body = "{\"productId\": " + created.getId() + ", \"quantity\": 2.0, \"requestId\": \"r-dec-2\"}";
        log.debug("소수 수량 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 소수 수량: requestId=r-dec-2");
    }

    @Test
    void JSON_형식이_잘못되면_500이_아닌_400을_반환한다() throws Exception {
        String body = "{\"productId\": 1, \"quantity\": ";
        log.debug("잘못된 JSON 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 잘못된 JSON");
    }

    @Test
    void 입고_수량이_1회_한도를_초과하면_409를_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKULIMITIN", "상품", 0L);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 10_001, "requestId", "r-limit-1");
        log.debug("한도 초과 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        log.error("예상된 409 QUANTITY_LIMIT_EXCEEDED 응답 확인: requestId=r-limit-1");
    }

    @Test
    void 출고_수량이_1회_한도를_초과하면_409를_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKULIMITOUT", "상품", 20_000L);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 10_001, "requestId", "r-limit-2");
        log.debug("한도 초과 출고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        log.error("예상된 409 QUANTITY_LIMIT_EXCEEDED 응답 확인: requestId=r-limit-2");
    }

    @Test
    void 입고_수량이_1회_한도와_같으면_정상_처리된다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKULIMITEQ", "상품", 0L);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 10_000, "requestId", "r-limit-3");
        log.debug("한도와 같은 수량 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterQuantity").value(10_000));
        log.info("한도와 같은 수량 입고 정상 처리 확인: requestId=r-limit-3");
    }

    @Test
    void 한도_초과와_다른_검증_오류가_함께_있으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productId", 1, "quantity", 10_001);
        log.debug("한도 초과 + requestId 누락 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/outbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 한도 초과보다 형식 오류 우선");
    }

    @Test
    void 입고로_재고가_BIGINT_범위를_넘으면_409를_반환한다() throws Exception {
        ProductRepository.UpsertResult created =
                productRepository.upsertProductStock("SKUOVERFLOW", "상품", Long.MAX_VALUE - 5);
        Map<String, Object> body = Map.of("productId", created.getId(), "quantity", 10, "requestId", "r-overflow-1");
        log.debug("BIGINT 범위 초과 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_QUANTITY_OVERFLOW"));
        log.error("예상된 409 STOCK_QUANTITY_OVERFLOW 응답 확인: requestId=r-overflow-1");
    }

    @Test
    void 상품코드로_업서트_입고시_재고가_BIGINT_범위를_넘으면_409를_반환한다() throws Exception {
        productRepository.upsertProductStock("SKUOVERFLOWUPSERT", "상품", Long.MAX_VALUE - 5);
        Map<String, Object> body = Map.of(
                "productCode", "SKUOVERFLOWUPSERT", "productName", "상품", "quantity", 10, "requestId", "r-overflow-2");
        log.debug("업서트 경로 BIGINT 범위 초과 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_QUANTITY_OVERFLOW"));
        log.error("예상된 409 STOCK_QUANTITY_OVERFLOW 응답 확인: requestId=r-overflow-2");
    }
    @Test
    void productId와_상품코드가_일치하지_않으면_400을_반환한다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUMINEAPI", "상품", 3L);
        Map<String, Object> body = Map.of(
                "productId", created.getId(), "productCode", "SKUOTHERAPI", "quantity", 2, "requestId", "r-mismatch-1");
        log.debug("productId-상품코드 불일치 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_MISMATCH"));
        log.error("예상된 400 PRODUCT_CODE_MISMATCH 응답 확인: requestId=r-mismatch-1");
    }
    @ParameterizedTest
    @ValueSource(strings = {"sku1", "Sku1", "SKU-1", "SKU 1", " SKU1", "SKU_1", "SKU#1", "SKU.1"})
    void 상품코드가_영문_대문자와_숫자로만_구성되지_않으면_400을_반환하고_상품이_생성되지_않는다(String productCode)
            throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", productCode, "productName", "상품", "quantity", 5, "requestId", "r-format-new");
        log.debug("형식이 잘못된 상품코드로 신규 입고 요청 전송: productCode=[{}]", productCode);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 잘못된 상품코드 형식: productCode=[{}]", productCode);

        assertThat(productRepository.findByProductCode(productCode)).isEmpty();
    }

    @Test
    void productId와_함께_보낸_상품코드_형식이_잘못되면_400을_반환하고_수량은_변하지_않는다() throws Exception {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUFMT1", "상품", 3L);
        Map<String, Object> body = Map.of(
                "productId", created.getId(), "productCode", "skufmt1", "quantity", 2, "requestId", "r-format-id");
        log.debug("productId + 형식이 잘못된 상품코드 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - productId + 잘못된 상품코드 형식: productId={}", created.getId());

        assertThat(productRepository.findById(created.getId()).orElseThrow().getQuantity()).isEqualTo(3L);
    }

    @Test
    void 영문_대문자와_숫자로_구성된_상품코드로_신규_입고하면_200을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKU1", "productName", "상품", "quantity", 5, "requestId", "r-format-ok");
        log.debug("올바른 형식의 상품코드로 신규 입고 요청 전송: body={}", body);

        mockMvc.perform(post("/api/v1/stocks/inbound")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKU1"))
                .andExpect(jsonPath("$.afterQuantity").value(5));
        log.info("올바른 형식의 상품코드 신규 입고 정상 응답 확인: requestId=r-format-ok");
    }
}
