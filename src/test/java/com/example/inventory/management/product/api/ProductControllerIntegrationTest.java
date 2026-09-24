package com.example.inventory.management.product.api;

import com.example.inventory.management.product.domain.ProductRepository;
import com.example.inventory.management.support.AbstractIntegrationTest;
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
import java.util.ArrayList;
import java.util.List;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Transactional
class ProductControllerIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductControllerIntegrationTest.class);

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 재고_조회_정상_요청시_200을_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUQ", "상품 Q", 42L);
        log.debug("재고 조회 테스트 상품 준비 완료: productId={}", productId);

        mockMvc.perform(get("/api/v1/products/{id}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCode").value("SKUQ"))
                .andExpect(jsonPath("$.productName").value("상품 Q"))
                .andExpect(jsonPath("$.quantity").value(42));
        log.info("재고 조회 API 정상 응답 확인: productId={}", productId);
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
    void 재고_이력_조회는_content_page_size_hasNext만_응답한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUH", "상품 H", 100L);
        log.debug("재고 이력 조회 테스트 상품 준비 완료: productId={}", productId);
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            requestIds.add(insertHistory(productId, BASE.plusSeconds(i)));
        }

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId)
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].requestId").value(requestIds.get(2)))
                .andExpect(jsonPath("$.content[1].requestId").value(requestIds.get(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.totalElements").doesNotExist())
                .andExpect(jsonPath("$.totalPages").doesNotExist());

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId)
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].requestId").value(requestIds.get(0)))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        log.info("재고 이력 페이징 응답 형식 확인: productId={}", productId);
    }

    @Test
    void 재고_이력_기본_페이지_크기는_10이다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUDEFAULT", "상품 D", 100L);
        for (int i = 0; i < 11; i++) {
            insertHistory(productId, BASE.plusSeconds(i));
        }

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(true));
        log.info("재고 이력 기본 페이지 크기 확인: productId={}", productId);
    }

    @Test
    void 마지막_페이지를_넘으면_빈_content와_hasNext_false를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUBEYOND", "상품 B", 100L);
        insertHistory(productId, BASE);

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(5))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 존재하지_않는_상품의_이력을_조회하면_404를_반환한다() throws Exception {
        log.debug("존재하지 않는 상품 이력 조회 요청 전송: productId=999999");
        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
        log.error("예상된 404 응답 확인 - 존재하지 않는 상품 이력 조회: productId=999999");
    }

    @Test
    void 재고_이력은_생성시각_역순으로_정렬되고_같은_시각이면_id_역순이다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUORDER", "상품 O", 100L);
        log.debug("이력 정렬 테스트 상품 준비 완료: productId={}", productId);
        // id order (insertion) deliberately disagrees with created_at order.
        String newest = insertHistory(productId, BASE.plusSeconds(60));
        String tieFirst = insertHistory(productId, BASE);
        String tieSecond = insertHistory(productId, BASE);
        String oldest = insertHistory(productId, BASE.minusSeconds(60));

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].requestId").value(newest))
                .andExpect(jsonPath("$.content[1].requestId").value(tieSecond))
                .andExpect(jsonPath("$.content[2].requestId").value(tieFirst))
                .andExpect(jsonPath("$.content[3].requestId").value(oldest));
        log.info("재고 이력 createdAt DESC, id DESC 정렬 확인: productId={}", productId);
    }

    @Test
    void sort_파라미터는_무시되고_고정_정렬이_유지된다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUSORTIGNORED", "상품 S", 100L);
        String older = insertHistory(productId, BASE);
        String newer = insertHistory(productId, BASE.plusSeconds(1));

        for (String sort : List.of("id,asc", "createdAt,asc", "quantity", "nope")) {
            mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("sort", sort))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].requestId").value(newer))
                    .andExpect(jsonPath("$.content[1].requestId").value(older));
        }
        log.info("sort 파라미터 무시 확인: productId={}", productId);
    }

    @Test
    void 음수_page는_400_VALIDATION_FAILED를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUBADPAGE", "상품 P", 100L);

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("page: 0 이상이어야 합니다."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        log.warn("예상된 400 응답 확인: page=-1");
    }

    @Test
    void size가_1보다_작으면_400_VALIDATION_FAILED를_반환한다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUBADSIZE", "상품 Z", 100L);

        for (String size : List.of("0", "-5")) {
            mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("size: 1 이상이어야 합니다."));
        }
        log.warn("예상된 400 응답 확인: size=0, size=-5");
    }

    @Test
    void size가_100보다_크면_오류_없이_100으로_제한된다() throws Exception {
        Long productId = insertProduct(jdbcTemplate, "SKUCLAMP", "상품 C", 100L);
        for (int i = 0; i < 101; i++) {
            insertHistory(productId, BASE.plusSeconds(i));
        }

        mockMvc.perform(get("/api/v1/products/{id}/stock-histories", productId).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(100))
                .andExpect(jsonPath("$.hasNext").value(true));
        log.info("페이지 크기 상한 100 확인: productId={}", productId);
    }

    // ---- 상품 목록 조회 (GET /api/v1/products) ----
    // Other test classes may leave committed products in the shared container. Rows inserted inside
    // this test's transaction get the highest ids, so they are the first rows of an unfiltered listing.

    @Test
    void 상품_목록은_기본_10건을_id_역순으로_반환한다() throws Exception {
        List<Long> ids = insertProducts("PLD", 11);

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.content[0].productId").value(ids.get(10)))
                .andExpect(jsonPath("$.content[1].productId").value(ids.get(9)))
                .andExpect(jsonPath("$.content[9].productId").value(ids.get(1)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(true));
        log.info("상품 목록 기본 페이지 크기와 id 역순 확인: newestId={}", ids.get(10));
    }

    @Test
    void 상품_목록은_다음_페이지로_이어서_조회된다() throws Exception {
        List<Long> ids = insertProducts("PLN", 3);

        mockMvc.perform(get("/api/v1/products").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].productId").value(ids.get(2)))
                .andExpect(jsonPath("$.content[1].productId").value(ids.get(1)))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/v1/products").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value(ids.get(0)))
                .andExpect(jsonPath("$.page").value(1));
    }

    @Test
    void 상품_목록은_content_page_size_hasNext와_요약_필드만_응답한다() throws Exception {
        String code = uniqueCode("PLJ");
        Long productId = insertProduct(jdbcTemplate, code, "상품 J", 42L);

        mockMvc.perform(get("/api/v1/products").param("productCode", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(4)))
                .andExpect(jsonPath("$", allOf(hasKey("content"), hasKey("page"), hasKey("size"), hasKey("hasNext"))))
                .andExpect(jsonPath("$.content[0]", aMapWithSize(4)))
                .andExpect(jsonPath("$.content[0].productId").value(productId))
                .andExpect(jsonPath("$.content[0].productCode").value(code))
                .andExpect(jsonPath("$.content[0].productName").value("상품 J"))
                .andExpect(jsonPath("$.content[0].quantity").value(42));
    }

    @Test
    void 상품코드로_필터링하면_일치하는_상품만_반환한다() throws Exception {
        String code = uniqueCode("PLF");
        Long productId = insertProduct(jdbcTemplate, code, "대상 상품", 7L);
        insertProduct(jdbcTemplate, code + "X", "접두어만 같은 상품", 7L);
        insertProduct(jdbcTemplate, uniqueCode("PLO"), "다른 상품", 7L);

        mockMvc.perform(get("/api/v1/products").param("productCode", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].productId").value(productId))
                .andExpect(jsonPath("$.hasNext").value(false));
        log.info("상품코드로 productId 조회 확인: productCode={}, productId={}", code, productId);
    }

    @Test
    void 일치하는_상품코드가_없으면_빈_목록을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("productCode", uniqueCode("NONE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void 형식이_잘못된_상품코드는_400_VALIDATION_FAILED를_반환한다() throws Exception {
        for (String productCode : List.of("sku1", "SKU-1", "SKU 1", "", "A".repeat(65))) {
            mockMvc.perform(get("/api/v1/products").param("productCode", productCode))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message", startsWith("productCode: ")))
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }
        mockMvc.perform(get("/api/v1/products").param("productCode", "sku1"))
                .andExpect(jsonPath("$.message").value("productCode: productCode는 영문 대문자와 숫자로만 구성되어야 합니다."));
        log.warn("예상된 400 응답 확인: 잘못된 productCode");
    }

    @Test
    void 최대_길이_64자의_상품코드는_허용된다() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("productCode", "A".repeat(64)))
                .andExpect(status().isOk());
    }

    @Test
    void 상품_목록의_잘못된_page_size는_400_VALIDATION_FAILED를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("page: 0 이상이어야 합니다."));
        for (String size : List.of("0", "-5")) {
            mockMvc.perform(get("/api/v1/products").param("size", size))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("size: 1 이상이어야 합니다."));
        }
        mockMvc.perform(get("/api/v1/products").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("page: 요청 값의 타입이 올바르지 않습니다."));
        log.warn("예상된 400 응답 확인: 상품 목록 page/size 오류");
    }

    @Test
    void 상품_목록_size가_100보다_크면_100으로_제한된다() throws Exception {
        List<Long> ids = insertProducts("PLC", 101);

        mockMvc.perform(get("/api/v1/products").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(100))
                .andExpect(jsonPath("$.content[0].productId").value(ids.get(100)))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    // ---- 상품 상세 조회 (GET /api/v1/products/{productId}) ----

    @Test
    void 상품_상세_조회는_상품_정보와_등록_변경_시각을_반환한다() throws Exception {
        String code = uniqueCode("PDT");
        Long productId = jdbcTemplate.queryForObject("""
                        INSERT INTO product (product_code, name, quantity, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?) RETURNING id
                        """,
                Long.class, code, "상세 상품", 15L,
                Timestamp.from(BASE), Timestamp.from(BASE.plusSeconds(3600)));

        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", aMapWithSize(6)))
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.productCode").value(code))
                .andExpect(jsonPath("$.productName").value("상세 상품"))
                .andExpect(jsonPath("$.quantity").value(15))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-01-01T01:00:00Z"));
        log.info("상품 상세 조회 응답 확인: productId={}", productId);
    }

    @Test
    void 존재하지_않는_상품의_상세를_조회하면_404를_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        log.error("예상된 404 PRODUCT_NOT_FOUND 응답 확인 - 상품 상세: productId=999999");
    }

    @Test
    void 상품_상세의_productId가_정수가_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private List<Long> insertProducts(String prefix, int count) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(insertProduct(jdbcTemplate, uniqueCode(prefix), "목록 상품 " + i, i));
        }
        return ids;
    }

    private String insertHistory(Long productId, Instant createdAt) {
        String requestId = newRequestId();
        jdbcTemplate.update("""
                        INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
                        VALUES (?, 'INBOUND', 10, 100, 110, ?, ?)
                        """,
                productId, requestId, Timestamp.from(createdAt));
        return requestId;
    }
}
