package com.example.inventory.management.common.config;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway's default PostgreSQL lock (pg_advisory_xact_lock) keeps a transaction open for the whole
 * migrate run, and CREATE/DROP INDEX CONCURRENTLY waits for every open transaction to finish, so the
 * two block each other. The session-level lock keeps migrations serialized without that transaction.
 */
class FlywayLockConfigTest extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void 마이그레이션_잠금은_트랜잭션이_아닌_세션_단위로_잡는다() {
        PostgreSQLConfigurationExtension postgresql = flyway.getConfiguration().getPluginRegister()
                .getExact(PostgreSQLConfigurationExtension.class);

        assertThat(postgresql.isTransactionalLock()).isFalse();
    }
}
