package com.example.inventory.management.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.UUID;

/**
 * Seeds product rows directly with SQL so tests don't depend on the production registration
 * path (which rejects an already-existing productCode). Runs in the caller's transaction when
 * the test is @Transactional (JdbcTemplate joins the JPA-managed connection).
 */
public final class ProductFixtures {

    private ProductFixtures() {
    }

    public static Long insertProduct(JdbcTemplate jdbcTemplate, String productCode, String name, long quantity) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO product (product_code, name, quantity) VALUES (?, ?, ?) RETURNING id",
                Long.class, productCode, name, quantity);
    }

    /** A random productCode that satisfies {@code ^[A-Z0-9]+$}, prefixed for readability in logs. */
    public static String uniqueCode(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }

    public static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
