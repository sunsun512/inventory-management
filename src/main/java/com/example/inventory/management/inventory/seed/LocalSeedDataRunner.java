package com.example.inventory.management.inventory.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads local development seed data (10,000 products with 8–12 stock histories each) when the
 * {@code seed} profile is active. This is deliberately not a Flyway migration: it is data for
 * local use only, so it lives outside {@code db/migration} and never runs in tests or other
 * environments. ApplicationRunners run after the context is refreshed, i.e. after Flyway has
 * migrated the schema.
 * <p>
 * The seed never deletes anything: it runs only when both product and stock_history are empty
 * and is skipped otherwise. The whole script runs in one transaction, and the script validates
 * the generated history chain itself, so a failure leaves the tables untouched.
 */
@Component
@Profile("seed")
public class LocalSeedDataRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSeedDataRunner.class);

    static final String SEED_SCRIPT = "db/seed/local-seed-data.sql";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public LocalSeedDataRunner(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /** Returns true if the seed data was inserted, false if it was skipped because data already exists. */
    public boolean seed() {
        String script = loadScript();
        Boolean seeded = transactionTemplate.execute(status -> {
            Boolean hasData = jdbcTemplate.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM product) OR EXISTS (SELECT 1 FROM stock_history)", Boolean.class);
            if (Boolean.TRUE.equals(hasData)) {
                log.info("기존 상품/재고 이력 데이터가 있어 시드 데이터 생성을 건너뜀");
                return false;
            }
            log.info("시드 데이터 생성 시작: script={}", SEED_SCRIPT);
            // The script contains several statements (including a DO $$ ... $$ block); PgJDBC
            // splits and runs them in order on this transaction's connection.
            jdbcTemplate.execute(script);
            return true;
        });

        if (Boolean.TRUE.equals(seeded)) {
            log.info("시드 데이터 생성 완료: products={}, stockHistories={}",
                    jdbcTemplate.queryForObject("SELECT count(*) FROM product", Long.class),
                    jdbcTemplate.queryForObject("SELECT count(*) FROM stock_history", Long.class));
        }
        return Boolean.TRUE.equals(seeded);
    }

    private static String loadScript() {
        try {
            return StreamUtils.copyToString(new ClassPathResource(SEED_SCRIPT).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("시드 스크립트를 읽을 수 없습니다: " + SEED_SCRIPT, e);
        }
    }
}
