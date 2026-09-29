package com.example.inventory.management.common.config;

import com.example.inventory.management.support.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownConfigTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownConfigTest.class);

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    void 종료_시_처리_중인_요청을_최대_20초까지_기다린다() {
        String shutdown = environment.getProperty("server.shutdown");
        Duration timeout = environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase", Duration.class);

        assertThat(shutdown).isEqualTo("graceful");
        assertThat(timeout).isEqualTo(Duration.ofSeconds(20));
        log.info("Graceful shutdown 설정 확인: server.shutdown={}, timeout-per-shutdown-phase={}", shutdown, timeout);
    }

    @Test
    void 커넥션_대기_시간이_종료_대기_시간보다_충분히_짧다() throws SQLException {
        long connectionTimeout = dataSource.unwrap(HikariDataSource.class).getConnectionTimeout();

        assertThat(connectionTimeout).isEqualTo(3000L);
        log.info("커넥션 대기 시간 확인: connection-timeout={}ms", connectionTimeout);
    }
}
