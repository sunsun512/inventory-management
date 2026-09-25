package com.example.inventory.management.stock.domain;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the stock_history table's indexes as created by the Flyway migrations. The history query
 * filters by product_id and sorts by created_at DESC, id DESC, so a single index in exactly that
 * order serves it without a sort step; the older (product_id, created_at DESC) and
 * (product_id, id DESC) indexes are replaced by it.
 */
class StockHistorySchemaMigrationTest extends AbstractIntegrationTest {

    private static final String HISTORY_INDEX = "idx_stock_history_product_id_created_at_id";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 이력_조회_인덱스는_상품_생성시각_id_내림차순으로_생성된다() {
        String indexdef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                String.class, HISTORY_INDEX);

        assertThat(indexdef).endsWith("(product_id, created_at DESC, id DESC)");
    }

    @Test
    void 이력_조회_인덱스는_조회에_쓸_수_있는_유효한_상태다() {
        // CREATE INDEX CONCURRENTLY가 도중에 실패하면 인덱스가 INVALID로 남아 플래너가 쓰지 않는다.
        Boolean valid = jdbcTemplate.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = ?::regclass", Boolean.class, HISTORY_INDEX);

        assertThat(valid).isTrue();
    }

    @Test
    void 정렬_키와_맞지_않는_기존_이력_인덱스는_제거된다() {
        assertThat(stockHistoryIndexNames()).containsExactlyInAnyOrder(
                "stock_history_pkey", "uk_stock_history_request_id", HISTORY_INDEX);
    }

    @Test
    @Transactional
    void 이력_조회는_별도_정렬_없이_인덱스_순서대로_읽는다() {
        // 테스트 데이터가 적으면 플래너가 Seq Scan + Sort를 고르므로, 인덱스 경로만 남겨 정렬 단계 유무를 본다.
        jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
        jdbcTemplate.execute("SET LOCAL enable_bitmapscan = off");

        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN SELECT * FROM stock_history WHERE product_id = 1"
                        + " ORDER BY created_at DESC, id DESC LIMIT 21",
                String.class));

        assertThat(plan).contains("Index Scan using " + HISTORY_INDEX).doesNotContain("Sort");
    }

    private List<String> stockHistoryIndexNames() {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'stock_history'",
                String.class);
    }
}
