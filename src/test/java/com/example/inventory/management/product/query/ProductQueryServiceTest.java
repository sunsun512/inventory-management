package com.example.inventory.management.product.query;

import com.example.inventory.management.common.exception.ProductNotFoundException;
import com.example.inventory.management.product.query.dto.StockQuantityResponse;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class ProductQueryServiceTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductQueryServiceTest.class);

    @Autowired
    private ProductQueryService productQueryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 현재_재고_조회는_상품_정보와_수량을_반환한다() {
        String code = uniqueCode("PQS");
        Long productId = insertProduct(jdbcTemplate, code, "재고 상품", 42L);

        StockQuantityResponse response = productQueryService.getStock(productId);

        assertThat(response).isEqualTo(new StockQuantityResponse(productId, code, "재고 상품", 42L));
        log.info("현재 재고 조회 결과 확인: productId={}", productId);
    }

    @Test
    void 존재하지_않는_상품의_현재_재고를_조회하면_상품없음_예외가_발생한다() {
        assertThatThrownBy(() -> productQueryService.getStock(999_999L))
                .isInstanceOf(ProductNotFoundException.class);
        log.error("예상된 ProductNotFoundException 발생 확인 - 현재 재고 조회: productId=999999");
    }
}
