package com.example.inventory.management.inventory.common.exception;

import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseTimeoutConfigTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTimeoutConfigTest.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기본_설정으로_락_대기_시간과_쿼리_실행_시간_제한이_적용된다() {
        String lockTimeout = jdbcTemplate.queryForObject("SHOW lock_timeout", String.class);
        String statementTimeout = jdbcTemplate.queryForObject("SHOW statement_timeout", String.class);

        assertThat(lockTimeout).isEqualTo("3s");
        assertThat(statementTimeout).isEqualTo("5s");
        log.info("DB 세션 타임아웃 설정 확인: lock_timeout={}, statement_timeout={}", lockTimeout, statementTimeout);
    }
}
