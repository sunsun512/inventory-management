package com.example.inventory.management.seed;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jdbc.autoconfigure.ApplicationDataSourceScriptDatabaseInitializer;
import org.springframework.boot.sql.autoconfigure.init.SqlInitializationProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.Properties;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs data.sql the way Spring Boot does at startup: the {@code spring.sql.init} settings are bound
 * from the real application.yml / application-local.yml and applied with Boot's own
 * {@link ApplicationDataSourceScriptDatabaseInitializer}, after Flyway has migrated the schema.
 * <p>
 * Each test gets its own fresh Postgres (instance @Container field → one container per test
 * method) instead of the shared AbstractIntegrationTest container, because the seed only inserts
 * into empty tables and other test classes leave committed rows in the shared one.
 * The session timeouts from application.yml are applied so the seed is verified under the same
 * statement_timeout the application uses.
 */
@Testcontainers
class LocalSeedDataSqlTest {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedDataSqlTest.class);

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.5");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("options", "-c lock_timeout=3s -c statement_timeout=5s");
        dataSource.setConnectionProperties(connectionProperties);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void local_프로필은_기본으로_data_sql을_실행하고_공통_설정은_실행하지_않는다() throws IOException {
        assertThat(sqlInitProperties("application.yml").getMode()).isEqualTo(DatabaseInitializationMode.EMBEDDED);
        assertThat(sqlInitProperties("application.yml", "application-local.yml").getMode())
                .isEqualTo(DatabaseInitializationMode.ALWAYS);
    }

    @Test
    void 빈_테이블이면_상품_1만개와_상품별_이력_8에서_12건을_넣는다() throws IOException {
        runDataSql();

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
    void 상품이_이미_있으면_시드를_건너뛰고_기존_데이터를_유지한다() throws IOException {
        Long existingId = insertProduct(jdbcTemplate, "SKU1", "상품 A", 10L);

        runDataSql();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Long.class, existingId))
                .isEqualTo(10L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM stock_history", Long.class)).isZero();
        log.info("기존 데이터가 있어 시드를 건너뜀 확인: productId={}", existingId);
    }

    /** Applies data.sql with the local profile's spring.sql.init settings, as a local startup does. */
    private void runDataSql() throws IOException {
        SqlInitializationProperties properties = sqlInitProperties("application.yml", "application-local.yml");
        new ApplicationDataSourceScriptDatabaseInitializer(dataSource, properties).initializeDatabase();
    }

    /**
     * Binds spring.sql.init from the given config files only (later files win, like profile files),
     * without system properties or OS environment, so a developer's DB_SEED_MODE does not leak in.
     */
    private static SqlInitializationProperties sqlInitProperties(String... configFiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String configFile : configFiles) {
            loader.load(configFile, new ClassPathResource(configFile))
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        }
        return Binder.get(environment)
                .bind("spring.sql.init", SqlInitializationProperties.class)
                .orElseGet(SqlInitializationProperties::new);
    }
}
