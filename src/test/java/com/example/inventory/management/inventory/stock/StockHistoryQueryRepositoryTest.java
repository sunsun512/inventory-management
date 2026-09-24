package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.response.PageResponse;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static com.example.inventory.management.inventory.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.inventory.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.inventory.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class StockHistoryQueryRepositoryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockHistoryQueryRepositoryTest.class);

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private StockHistoryQueryRepository stockHistoryQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 생성시각_역순으로_정렬된다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("QOC"), "정렬 상품", 100L);
        // Inserted (id ascending) with created_at going back in time, so id order and time order disagree.
        Long oldestId = insertHistory(productId, BASE.minusSeconds(120));
        Long newestId = insertHistory(productId, BASE);
        Long middleId = insertHistory(productId, BASE.minusSeconds(60));

        PageResponse<StockHistory> page = stockHistoryQueryRepository.findByProductId(productId, 0, 10);

        assertThat(page.content()).extracting(StockHistory::getId).containsExactly(newestId, middleId, oldestId);
        assertThat(page.hasNext()).isFalse();
        log.info("재고 이력 생성시각 역순 정렬 확인: productId={}", productId);
    }

    @Test
    void 생성시각이_같으면_id_역순으로_정렬된다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("QTB"), "동일 시각 상품", 100L);
        Long first = insertHistory(productId, BASE);
        Long second = insertHistory(productId, BASE);
        Long third = insertHistory(productId, BASE);
        Long older = insertHistory(productId, BASE.minusSeconds(1));

        PageResponse<StockHistory> page = stockHistoryQueryRepository.findByProductId(productId, 0, 10);

        assertThat(page.content()).extracting(StockHistory::getId).containsExactly(third, second, first, older);
        log.info("동일 생성시각 id 역순 정렬 확인: productId={}", productId);
    }

    @Test
    void 페이지를_나누어_조회하고_다음_페이지_여부를_반환한다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("QPG"), "페이징 상품", 100L);
        Long[] ids = new Long[5];
        for (int i = 0; i < 5; i++) {
            ids[i] = insertHistory(productId, BASE.plusSeconds(i));
        }

        PageResponse<StockHistory> first = stockHistoryQueryRepository.findByProductId(productId, 0, 2);
        PageResponse<StockHistory> second = stockHistoryQueryRepository.findByProductId(productId, 1, 2);
        PageResponse<StockHistory> last = stockHistoryQueryRepository.findByProductId(productId, 2, 2);
        PageResponse<StockHistory> beyond = stockHistoryQueryRepository.findByProductId(productId, 3, 2);

        assertThat(first.content()).extracting(StockHistory::getId).containsExactly(ids[4], ids[3]);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.content()).extracting(StockHistory::getId).containsExactly(ids[2], ids[1]);
        assertThat(second.hasNext()).isTrue();
        assertThat(last.content()).extracting(StockHistory::getId).containsExactly(ids[0]);
        assertThat(last.hasNext()).isFalse();
        assertThat(beyond.content()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
        assertThat(beyond.page()).isEqualTo(3);
        assertThat(beyond.size()).isEqualTo(2);
        log.info("재고 이력 페이징 확인: productId={}", productId);
    }

    @Test
    void 다른_상품의_이력은_포함하지_않는다() {
        Long productId = insertProduct(jdbcTemplate, uniqueCode("QMY"), "내 상품", 100L);
        Long otherId = insertProduct(jdbcTemplate, uniqueCode("QOT"), "다른 상품", 100L);
        Long mine = insertHistory(productId, BASE);
        insertHistory(otherId, BASE.plusSeconds(1));

        PageResponse<StockHistory> page = stockHistoryQueryRepository.findByProductId(productId, 0, 10);

        assertThat(page.content()).extracting(StockHistory::getId).containsExactly(mine);
    }

    private Long insertHistory(Long productId, Instant createdAt) {
        return jdbcTemplate.queryForObject("""
                        INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
                        VALUES (?, 'INBOUND', 10, 100, 110, ?, ?) RETURNING id
                        """,
                Long.class, productId, newRequestId(), Timestamp.from(createdAt));
    }
}
