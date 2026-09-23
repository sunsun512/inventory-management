package com.example.inventory.management.inventory.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton Testcontainers pattern: the container is started once, manually, and never
 * stopped explicitly (Ryuk/JVM shutdown reclaims it). This field is inherited (shared static
 * storage) by every subclass, so all integration test classes reuse the same running
 * container and the same mapped port. Using @Container/@Testcontainers instead would give
 * each independent top-level test class its own start/stop lifecycle on this same shared
 * field, stopping the container out from under a Spring context cached by an earlier class.
 */
@SpringBootTest
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        postgres.start();
    }
}
