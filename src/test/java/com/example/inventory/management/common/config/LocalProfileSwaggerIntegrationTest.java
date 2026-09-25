package com.example.inventory.management.common.config;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the real {@code local} profile so the check covers application-local.yml as loaded by Spring, not just
 * its text. {@code @ServiceConnection} points the datasource at the Testcontainers Postgres instead of
 * localhost:5432, and the local seed (data.sql) and SQL logging are switched off to keep this context light.
 */
@SpringBootTest(properties = {
        "rate-limit.post.capacity=1000000",
        "spring.sql.init.mode=never",
        "logging.level.org.hibernate.SQL=INFO",
        "logging.level.org.hibernate.orm.jdbc.bind=INFO"
})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class LocalProfileSwaggerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void local_프로필에서는_OpenAPI_문서를_제공한다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Inventory Management API"));
    }

    @Test
    void local_프로필에서는_Swagger_UI를_제공한다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
