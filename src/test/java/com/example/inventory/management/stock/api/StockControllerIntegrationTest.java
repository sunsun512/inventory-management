package com.example.inventory.management.stock.api;

import com.example.inventory.management.product.domain.ProductRepository;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class StockControllerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockControllerIntegrationTest.class);

    private static final String INBOUND = "/api/v1/stocks/inbound";
    private static final String OUTBOUND = "/api/v1/stocks/outbound";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 입고_수량이_0이하면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKU1", "productName", "상품", "quantity", 0, "requestId", newRequestId());
        log.debug("입고 수량 0 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인: 입고 수량 0");
    }

    @Test
    void 상품_식별자가_없으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("quantity", 5, "requestId", newRequestId());
        log.debug("상품 식별자 누락 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 응답 확인 - 상품 식별자 누락");
    }

    @Test
    void requestId가_없으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productCode", "SKU1", "productName", "상품", "quantity", 5);
        log.debug("requestId 누락 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 응답 확인 - requestId 누락");
    }

    @Test
    void 입고_정상_요청시_200과_예상된_응답을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKUHAPPY", "productName", "상품", "quantity", 10, "requestId", newRequestId());
        log.debug("정상 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKUHAPPY"))
                .andExpect(jsonPath("$.type").value("INBOUND"))
                .andExpect(jsonPath("$.beforeQuantity").value(0))
                .andExpect(jsonPath("$.afterQuantity").value(10));
        log.info("입고 API 정상 응답 확인: productCode=SKUHAPPY");
    }

    @Test
    void 존재하지_않는_상품을_출고하면_404를_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productId", 999_999, "productCode", "SKUANY", "quantity", 1, "requestId", newRequestId());
        log.debug("존재하지 않는 상품 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.error("예상된 404 PRODUCT_NOT_FOUND 응답 확인: productId=999999");
    }

    @Test
    void 존재하지_않는_상품에_입고하면_404를_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productId", 999_999, "productCode", "SKUANY", "quantity", 1, "requestId", newRequestId());
        log.debug("존재하지 않는 상품 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.error("예상된 404 PRODUCT_NOT_FOUND 응답 확인(입고): productId=999999");
    }

    @Test
    void 재고가_부족하면_409를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKULOW", "상품", 3L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKULOW", "quantity", 5, "requestId", newRequestId());
        log.debug("재고 부족 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        log.error("예상된 409 INSUFFICIENT_STOCK 응답 확인: productId={}", productId);
    }

    @Test
    void 출고_정상_요청시_200과_예상된_응답을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOK", "상품", 10L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKUOK", "quantity", 4, "requestId", newRequestId());
        log.debug("정상 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("OUTBOUND"))
                .andExpect(jsonPath("$.productCode").value("SKUOK"))
                .andExpect(jsonPath("$.beforeQuantity").value(10))
                .andExpect(jsonPath("$.afterQuantity").value(6));
        log.info("출고 API 정상 응답 확인: productId={}", productId);
    }

    @Test
    void 출고시_productCode가_없으면_400을_반환하고_수량은_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOUTNOCODE", "상품", 10L);
        Map<String, Object> body = Map.of("productId", productId, "quantity", 1, "requestId", newRequestId());
        log.debug("productCode 누락 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 출고 productCode 누락: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 출고시_productId가_없으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productCode", "SKUOUTNOID", "quantity", 1, "requestId", newRequestId());
        log.debug("productId 누락 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productId")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 출고 productId 누락");
    }

    @Test
    void 출고시_productId와_상품코드가_일치하지_않으면_400을_반환하고_수량은_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOUTMINEAPI", "상품", 10L);
        insertProduct(jdbcTemplate, "SKUOUTOTHERAPI", "다른 상품", 10L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKUOUTOTHERAPI", "quantity", 2, "requestId", newRequestId());
        log.debug("productId-상품코드 불일치 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_MISMATCH"));
        log.error("예상된 400 PRODUCT_CODE_MISMATCH 응답 확인(출고): productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
        assertThat(productRepository.findByProductCode("SKUOUTOTHERAPI").orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 출고시_productName을_보내면_알_수_없는_필드로_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOUTNAME", "상품", 10L);
        Map<String, Object> body = Map.of("productId", productId, "productCode", "SKUOUTNAME",
                "productName", "상품", "quantity", 1, "requestId", newRequestId());
        log.debug("productName 포함 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productName")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 출고 productName: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 기존_상품_입고시_productCode가_없으면_400을_반환하고_수량은_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUINNOCODE", "상품", 10L);
        Map<String, Object> body = Map.of("productId", productId, "quantity", 1, "requestId", newRequestId());
        log.debug("productCode 누락 기존 상품 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 입고 productCode 누락: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 신규_상품_입고시_productName이_없으면_400을_반환하고_상품이_생성되지_않는다() throws Exception {
        Map<String, Object> body = Map.of("productCode", "SKUNONAME", "quantity", 1, "requestId", newRequestId());
        log.debug("productName 누락 신규 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productName")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 신규 입고 productName 누락");

        assertThat(productRepository.findByProductCode("SKUNONAME")).isEmpty();
    }

    @Test
    void 이미_존재하는_상품코드로_신규_입고하면_409를_반환하고_재고는_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUEXISTSAPI", "기존 상품", 10L);
        Map<String, Object> body = Map.of(
                "productCode", "SKUEXISTSAPI", "productName", "새 상품", "quantity", 5, "requestId", newRequestId());
        log.debug("이미 존재하는 상품코드로 신규 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_ALREADY_EXISTS"));
        log.error("예상된 409 PRODUCT_CODE_ALREADY_EXISTS 응답 확인: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 소수_수량으로_출고하면_잘라내지_않고_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUDEC", "상품", 10L);
        String body = "{\"productId\": " + productId + ", \"productCode\": \"SKUDEC\", \"quantity\": 1.9, \"requestId\": \""
                + newRequestId() + "\"}";
        log.debug("소수 수량 출고 요청 전송: body={}", body);

        postRaw(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 소수 수량(출고)");
    }

    @Test
    void 소수_수량으로_입고하면_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUDEC2", "상품", 10L);
        String body = "{\"productId\": " + productId + ", \"productCode\": \"SKUDEC2\", \"quantity\": 2.0, \"requestId\": \""
                + newRequestId() + "\"}";
        log.debug("소수 수량 입고 요청 전송: body={}", body);

        postRaw(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 소수 수량(입고)");
    }

    @Test
    void 문자열_수량으로_입고하면_변환하지_않고_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUSTRQTYIN", "상품", 10L);
        String body = "{\"productId\": " + productId + ", \"productCode\": \"SKUSTRQTYIN\", \"quantity\": \"5\", \"requestId\": \""
                + newRequestId() + "\"}";
        log.debug("문자열 수량 입고 요청 전송: body={}", body);

        postRaw(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 문자열 수량(입고)");

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 문자열_수량으로_출고하면_변환하지_않고_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUSTRQTYOUT", "상품", 10L);
        String body = "{\"productId\": " + productId + ", \"productCode\": \"SKUSTRQTYOUT\", \"quantity\": \"5\", \"requestId\": \""
                + newRequestId() + "\"}";
        log.debug("문자열 수량 출고 요청 전송: body={}", body);

        postRaw(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 문자열 수량(출고)");

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 문자열_productId로_요청하면_변환하지_않고_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUSTRID", "상품", 10L);
        String body = "{\"productId\": \"" + productId + "\", \"productCode\": \"SKUSTRID\", \"quantity\": 1, \"requestId\": \""
                + newRequestId() + "\"}";
        log.debug("문자열 productId 출고 요청 전송: body={}", body);

        postRaw(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        postRaw(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 문자열 productId");

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 알_수_없는_필드가_있으면_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUUNKNOWN", "상품", 10L);
        Map<String, Object> body = Map.of("productId", productId, "productCode", "SKUUNKNOWN",
                "quantity", 1, "requestId", newRequestId(), "memo", "알 수 없는 필드");
        log.debug("알 수 없는 필드 포함 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("memo")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 알 수 없는 필드 memo");

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "r-1",
            "",
            "123e4567-e89b-12d3-a456-42661417400",
            "123e4567-e89b-12d3-a456-4266141740000",
            "123e4567e89b12d3a456426614174000",
            "{123e4567-e89b-12d3-a456-426614174000}",
            "g23e4567-e89b-12d3-a456-426614174000",
            " 123e4567-e89b-12d3-a456-426614174000"
    })
    void requestId가_UUID_형식이_아니면_400을_반환한다(String requestId) throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKUUUIDBAD", "productName", "상품", "quantity", 1, "requestId", requestId);
        log.debug("UUID 형식이 아닌 requestId 입고 요청 전송: requestId=[{}]", requestId);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("requestId")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - requestId 형식 오류: requestId=[{}]", requestId);

        assertThat(productRepository.findByProductCode("SKUUUIDBAD")).isEmpty();
    }

    @Test
    void 숫자_requestId는_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUNUMREQ", "상품", 10L);
        String body = "{\"productId\": " + productId + ", \"productCode\": \"SKUNUMREQ\", \"quantity\": 1, \"requestId\": 123}";
        log.debug("숫자 requestId 출고 요청 전송: body={}", body);

        postRaw(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 숫자 requestId");

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 대문자_UUID_requestId는_허용되고_소문자로_정규화된다() throws Exception {
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productCode", "SKUUUIDUPPER", "productName", "상품", "quantity", 1,
                "requestId", requestId.toUpperCase(Locale.ROOT));
        log.debug("대문자 UUID requestId 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId));
        log.info("대문자 UUID requestId 소문자 정규화 확인: requestId={}", requestId);
    }

    @Test
    void 입고_수량이_1회_한도를_초과하면_409를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKULIMITIN", "상품", 0L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKULIMITIN", "quantity", 10_001, "requestId", newRequestId());
        log.debug("한도 초과 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        log.error("예상된 409 QUANTITY_LIMIT_EXCEEDED 응답 확인(입고)");
    }

    @Test
    void 출고_수량이_1회_한도를_초과하면_409를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKULIMITOUT", "상품", 20_000L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKULIMITOUT", "quantity", 10_001, "requestId", newRequestId());
        log.debug("한도 초과 출고 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"));
        log.error("예상된 409 QUANTITY_LIMIT_EXCEEDED 응답 확인(출고)");
    }

    @Test
    void 입고_수량이_1회_한도와_같으면_정상_처리된다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKULIMITEQ", "상품", 0L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKULIMITEQ", "quantity", 10_000, "requestId", newRequestId());
        log.debug("한도와 같은 수량 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.afterQuantity").value(10_000));
        log.info("한도와 같은 수량 입고 정상 처리 확인: productId={}", productId);
    }

    @Test
    void 한도_초과와_다른_검증_오류가_함께_있으면_400을_반환한다() throws Exception {
        Map<String, Object> body = Map.of("productId", 1, "productCode", "SKU1", "quantity", 10_001);
        log.debug("한도 초과 + requestId 누락 요청 전송: body={}", body);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 한도 초과보다 형식 오류 우선");
    }

    @Test
    void 입고로_재고가_BIGINT_범위를_넘으면_409를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOVERFLOW", "상품", Long.MAX_VALUE - 5);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKUOVERFLOW", "quantity", 10, "requestId", newRequestId());
        log.debug("BIGINT 범위 초과 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_QUANTITY_OVERFLOW"));
        log.error("예상된 409 STOCK_QUANTITY_OVERFLOW 응답 확인: productId={}", productId);
    }

    @Test
    void productId와_상품코드가_일치하지_않으면_400을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUMINEAPI", "상품", 3L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "SKUOTHERAPI", "quantity", 2, "requestId", newRequestId());
        log.debug("productId-상품코드 불일치 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_MISMATCH"));
        log.error("예상된 400 PRODUCT_CODE_MISMATCH 응답 확인: productId={}", productId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sku1", "Sku1", "SKU-1", "SKU 1", " SKU1", "SKU_1", "SKU#1", "SKU.1"})
    void 상품코드가_영문_대문자와_숫자로만_구성되지_않으면_400을_반환하고_상품이_생성되지_않는다(String productCode)
            throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", productCode, "productName", "상품", "quantity", 5, "requestId", newRequestId());
        log.debug("형식이 잘못된 상품코드로 신규 입고 요청 전송: productCode=[{}]", productCode);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 잘못된 상품코드 형식: productCode=[{}]", productCode);

        assertThat(productRepository.findByProductCode(productCode)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sku1", "SKU-1", "SKU 1"})
    void 출고시_상품코드_형식이_잘못되면_400을_반환하고_수량은_변하지_않는다(String productCode) throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOUTFMT", "상품", 10L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", productCode, "quantity", 1, "requestId", newRequestId());
        log.debug("형식이 잘못된 상품코드로 출고 요청 전송: productCode=[{}]", productCode);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 출고 상품코드 형식 오류: productCode=[{}]", productCode);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 신규_입고의_상품코드가_64자를_넘으면_길이_제한_메시지로_400을_반환하고_상품이_생성되지_않는다() throws Exception {
        String productCode = "A".repeat(65);
        Map<String, Object> body = Map.of(
                "productCode", productCode, "productName", "상품", "quantity", 5, "requestId", newRequestId());
        log.debug("64자 초과 상품코드로 신규 입고 요청 전송");

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("productCode: productCode는 64자 이하여야 합니다."));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 64자 초과 상품코드 입고");

        assertThat(productRepository.findByProductCode(productCode)).isEmpty();
    }

    @Test
    void 출고의_상품코드가_64자를_넘으면_길이_제한_메시지로_400을_반환하고_수량은_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUOUTLEN", "상품", 10L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "A".repeat(65), "quantity", 1, "requestId", newRequestId());
        log.debug("64자 초과 상품코드로 출고 요청 전송: productId={}", productId);

        postJson(OUTBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("productCode: productCode는 64자 이하여야 합니다."));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - 64자 초과 상품코드 출고: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void productId와_함께_보낸_상품코드_형식이_잘못되면_400을_반환하고_수량은_변하지_않는다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUFMT1", "상품", 3L);
        Map<String, Object> body = Map.of(
                "productId", productId, "productCode", "skufmt1", "quantity", 2, "requestId", newRequestId());
        log.debug("productId + 형식이 잘못된 상품코드 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value(containsString("productCode는 영문 대문자와 숫자로만 구성되어야 합니다.")));
        log.error("예상된 400 VALIDATION_FAILED 응답 확인 - productId + 잘못된 상품코드 형식: productId={}", productId);

        assertThat(productRepository.findById(productId).orElseThrow().getQuantity()).isEqualTo(3L);
    }

    @Test
    void 영문_대문자와_숫자로_구성된_상품코드로_신규_입고하면_200을_반환한다() throws Exception {
        Map<String, Object> body = Map.of(
                "productCode", "SKU1", "productName", "상품", "quantity", 5, "requestId", newRequestId());
        log.debug("올바른 형식의 상품코드로 신규 입고 요청 전송: body={}", body);

        postJson(INBOUND, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKU1"))
                .andExpect(jsonPath("$.afterQuantity").value(5));
        log.info("올바른 형식의 상품코드 신규 입고 정상 응답 확인: productCode=SKU1");
    }

    private ResultActions postJson(String path, Map<String, Object> body) throws Exception {
        return postRaw(path, objectMapper.writeValueAsString(body));
    }

    private ResultActions postRaw(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType("application/json").content(body));
    }
}
