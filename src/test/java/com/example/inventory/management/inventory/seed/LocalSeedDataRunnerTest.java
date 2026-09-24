package com.example.inventory.management.inventory.seed;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static com.example.inventory.management.inventory.support.ProductFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each test gets its own fresh Postgres (instance @Container field → one container per test
 * method) instead of the shared AbstractIntegrationTest container, because the seed only runs
 * against empty tables and other test classes leave committed rows in the shared one.
 * The session timeouts from application.yml are applied so the seed is verified under the same
 * statement_timeout the application uses.
 */
@Testcontainers
class LocalSeedDataRunnerTest {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedDataRunnerTest.class);

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");

    private JdbcTemplate jdbcTemplate;
    private LocalSeedDataRunner runner;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("options", "-c lock_timeout=3s -c statement_timeout=5s");
        dataSource.setConnectionProperties(connectionProperties);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        runner = new LocalSeedDataRunner(jdbcTemplate, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void 빈_테이블이면_상품_1만개와_상품별_이력_8에서_12건을_넣는다() {
        boolean seeded = runner.seed();

        assertThat(seeded).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(10_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM (SELECT product_id FROM stock_history GROUP BY product_id "
                        + "HAVING count(*) BETWEEN 8 AND 12) t", Long.class)).isEqualTo(10_000L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product p JOIN LATERAL (SELECT after_quantity FROM stock_history h "
                        + "WHERE h.product_id = p.id ORDER BY h.id DESC LIMIT 1) l ON true "
                        + "WHERE p.quantity <> l.after_quantity", Long.class)).isZero();
        log.info("시드 데이터 생성 확인: products=10000");
    }

    @Test
    void 상품이_이미_있으면_시드를_건너뛰고_기존_데이터를_유지한다() {
        Long existingId = insertProduct(jdbcTemplate, "SKU1", "상품 A", 10L);

        boolean seeded = runner.seed();

        assertThat(seeded).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Long.class, existingId))
                .isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_history", Long.class)).isZero();
        log.info("기존 데이터가 있어 시드를 건너뜀 확인: productId={}", existingId);
    }
}
