package com.example.inventory.management.stock;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that one inbound/outbound mutation uses a single application-side timestamp for the
 * product's updated_at (and created_at when the product is registered), the history's created_at
 * and the response's createdAt, and that the value is microsecond-precise so what the API returns
 * is exactly what Postgres TIMESTAMPTZ stores. Not @Transactional: each request commits on its own.
 */
@AutoConfigureMockMvc
class StockTimestampIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기존_상품_입고는_상품_수정시각_이력_생성시각_응답_시각이_모두_같다() throws Exception {
        String code = uniqueCode("TSIN");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        Instant createdBefore = productTimestamp(productId, "created_at");
        String requestId = newRequestId();

        Instant responseCreatedAt = postAndGetCreatedAt("/api/v1/stocks/inbound",
                Map.of("productId", productId, "productCode", code, "quantity", 3, "requestId", requestId));

        assertThat(historyCreatedAt(requestId)).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "updated_at")).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "created_at")).isEqualTo(createdBefore);
        assertMicrosecondPrecision(responseCreatedAt);
    }

    @Test
    void 신규_상품_입고는_상품_등록시각_수정시각_이력_생성시각_응답_시각이_모두_같다() throws Exception {
        String code = uniqueCode("TSNEW");
        String requestId = newRequestId();

        Instant responseCreatedAt = postAndGetCreatedAt("/api/v1/stocks/inbound",
                Map.of("productCode", code, "productName", "신규 상품", "quantity", 5, "requestId", requestId));

        Long productId = jdbcTemplate.queryForObject("SELECT id FROM product WHERE product_code = ?", Long.class, code);
        assertThat(historyCreatedAt(requestId)).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "created_at")).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "updated_at")).isEqualTo(responseCreatedAt);
        assertMicrosecondPrecision(responseCreatedAt);
    }

    @Test
    void 출고는_상품_수정시각_이력_생성시각_응답_시각이_모두_같다() throws Exception {
        String code = uniqueCode("TSOUT");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        Instant createdBefore = productTimestamp(productId, "created_at");
        String requestId = newRequestId();

        Instant responseCreatedAt = postAndGetCreatedAt("/api/v1/stocks/outbound",
                Map.of("productId", productId, "productCode", code, "quantity", 4, "requestId", requestId));

        assertThat(historyCreatedAt(requestId)).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "updated_at")).isEqualTo(responseCreatedAt);
        assertThat(productTimestamp(productId, "created_at")).isEqualTo(createdBefore);
        assertMicrosecondPrecision(responseCreatedAt);
    }

    private Instant postAndGetCreatedAt(String url, Map<String, Object> body) throws Exception {
        String json = mockMvc.perform(post(url)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(json);
        return Instant.parse(response.get("createdAt").asString());
    }

    private Instant productTimestamp(Long productId, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM product WHERE id = ?", Timestamp.class, productId).toInstant();
    }

    private Instant historyCreatedAt(String requestId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_at FROM stock_history WHERE request_id = ?", Timestamp.class, requestId).toInstant();
    }

    private static void assertMicrosecondPrecision(Instant instant) {
        assertThat(instant.getNano() % 1_000).as("sub-microsecond nanos of %s", instant).isZero();
    }
}
