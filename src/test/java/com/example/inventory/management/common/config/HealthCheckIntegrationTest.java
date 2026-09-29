package com.example.inventory.management.common.config;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class HealthCheckIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @AfterEach
    void restoreReadiness() {
        // 스프링 컨텍스트는 테스트 간에 공유되므로 바꾼 readiness 상태를 원래대로 돌려놓는다.
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void 전체_헬스체크는_DB를_포함하고_상세_정보는_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.db.details").doesNotExist());
        log.info("전체 헬스체크 응답 확인: db 포함, 상세 정보 비노출");
    }

    @Test
    void liveness는_프로세스_상태만_보고_DB를_포함하지_않는다() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.livenessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db").doesNotExist());
        log.info("liveness 응답 확인: livenessState만 포함");
    }

    @Test
    void readiness는_트래픽_수용_상태와_DB를_함께_확인한다() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        log.info("readiness 응답 확인: readinessState, db 포함");
    }

    @Test
    void 메인_포트의_livez와_readyz로도_프로브를_조회할_수_있다() throws Exception {
        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        log.info("추가 프로브 경로 확인: /livez, /readyz");
    }

    @Test
    void 트래픽을_거부하는_상태가_되면_readiness만_503을_반환한다() throws Exception {
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("OUT_OF_SERVICE"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        log.info("REFUSING_TRAFFIC 상태 확인: readiness 503, liveness 200");
    }

    @Test
    void health_외의_actuator_엔드포인트는_노출하지_않는다() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isNotFound());
        log.info("health 외 actuator 엔드포인트 비노출 확인");
    }
}
