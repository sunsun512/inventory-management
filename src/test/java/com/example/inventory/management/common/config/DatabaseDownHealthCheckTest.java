package com.example.inventory.management.common.config;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB 헬스 인디케이터를 항상 DOWN인 것으로 바꿔 DB 장애 상황을 흉내 낸다.
 * dbHealthIndicator 이름의 빈이 있으면 기본 DB 헬스 인디케이터 자동 설정은 등록되지 않는다.
 */
@AutoConfigureMockMvc
@Import(DatabaseDownHealthCheckTest.DatabaseDownConfig.class)
class DatabaseDownHealthCheckTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseDownHealthCheckTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void DB가_DOWN이면_readiness는_503이고_liveness는_200이다() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("DOWN"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        log.info("DB 장애 시 프로브 확인: readiness 503, liveness 200");
    }

    @TestConfiguration
    static class DatabaseDownConfig {

        @Bean
        HealthIndicator dbHealthIndicator() {
            return () -> Health.down().withDetail("reason", "test").build();
        }
    }
}
