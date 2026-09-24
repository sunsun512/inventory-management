package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.response.PageResponse;
import com.example.inventory.management.inventory.product.dto.ProductSummaryResponse;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static com.example.inventory.management.inventory.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.inventory.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Other test classes may leave committed products in the shared container, so these tests only
 * rely on the rows they insert: inside this transaction they get the highest ids, so they are the
 * first rows of an unfiltered id-DESC listing.
 */
@Transactional
class ProductQueryRepositoryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryRepositoryTest.class);

    @Autowired
    private ProductQueryRepository productQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 상품_목록은_id_역순이고_요약_컬럼을_담는다() {
        String firstCode = uniqueCode("PQA");
        String secondCode = uniqueCode("PQB");
        Long first = insertProduct(jdbcTemplate, firstCode, "상품 A", 5L);
        Long second = insertProduct(jdbcTemplate, secondCode, "상품 B", 7L);

        PageResponse<ProductSummaryResponse> page = productQueryRepository.findSummaries(null, 0, 2);

        assertThat(page.content()).containsExactly(
                new ProductSummaryResponse(second, secondCode, "상품 B", 7L),
                new ProductSummaryResponse(first, firstCode, "상품 A", 5L));
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(2);
        log.info("상품 목록 id 역순 및 요약 컬럼 확인: firstId={}, secondId={}", first, second);
    }

    @Test
    void 한_건을_더_읽어_다음_페이지_여부를_판단한다() {
        Long[] ids = new Long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = insertProduct(jdbcTemplate, uniqueCode("PQP"), "페이징 상품 " + i, 1L);
        }

        PageResponse<ProductSummaryResponse> first = productQueryRepository.findSummaries(null, 0, 2);
        PageResponse<ProductSummaryResponse> second = productQueryRepository.findSummaries(null, 1, 2);

        assertThat(first.content()).extracting(ProductSummaryResponse::productId).containsExactly(ids[2], ids[1]);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.content()).extracting(ProductSummaryResponse::productId).first().isEqualTo(ids[0]);
    }

    @Test
    void 상품코드를_주면_정확히_일치하는_상품만_조회한다() {
        String code = uniqueCode("PQF");
        Long target = insertProduct(jdbcTemplate, code, "대상 상품", 3L);
        // A code that merely starts with the filter must not match: the filter is exact, not a prefix search.
        insertProduct(jdbcTemplate, code + "X", "접두어만 같은 상품", 3L);
        insertProduct(jdbcTemplate, uniqueCode("PQO"), "다른 상품", 3L);

        PageResponse<ProductSummaryResponse> page = productQueryRepository.findSummaries(code, 0, 10);

        assertThat(page.content()).containsExactly(new ProductSummaryResponse(target, code, "대상 상품", 3L));
        assertThat(page.hasNext()).isFalse();
        log.info("상품코드 정확 일치 조회 확인: productCode={}, productId={}", code, target);
    }

    @Test
    void 일치하는_상품코드가_없으면_빈_페이지를_반환한다() {
        insertProduct(jdbcTemplate, uniqueCode("PQM"), "상품", 1L);

        PageResponse<ProductSummaryResponse> page = productQueryRepository.findSummaries(uniqueCode("NONE"), 0, 10);

        assertThat(page.content()).isEmpty();
        assertThat(page.hasNext()).isFalse();
    }
}
