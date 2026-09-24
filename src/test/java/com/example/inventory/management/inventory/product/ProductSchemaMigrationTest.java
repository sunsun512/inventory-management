package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the product table's indexes as created by the Flyway migrations. No API filters or sorts
 * by product name, so the name index (idx_product_name) is not created: it would only add write
 * cost to every product insert.
 */
class ProductSchemaMigrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductSchemaMigrationTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 상품명_인덱스는_생성되지_않는다() {
        List<String> indexes = productIndexNames();
        log.info("product 테이블 인덱스: {}", indexes);

        assertThat(indexes).doesNotContain("idx_product_name");
    }

    @Test
    void 기본키와_상품코드_유니크_인덱스는_유지된다() {
        assertThat(productIndexNames()).containsExactlyInAnyOrder("product_pkey", "uk_product_product_code");
    }

    private List<String> productIndexNames() {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'product'",
                String.class);
    }
}
