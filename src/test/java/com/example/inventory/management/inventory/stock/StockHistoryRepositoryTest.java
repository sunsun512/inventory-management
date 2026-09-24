package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.example.inventory.management.inventory.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.inventory.support.ProductFixtures.newRequestId;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class StockHistoryRepositoryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockHistoryRepositoryTest.class);

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void setUp() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void 해당_상품의_이력만_id_역순으로_조회한다() {
        Long productId = insertProduct(jdbcTemplate, "SKUQD1", "상품 1", 100L);
        Long otherProductId = insertProduct(jdbcTemplate, "SKUQD2", "상품 2", 100L);
        List<String> requestIds = insertHistories(productId, 3);
        insertHistories(otherProductId, 2);
        log.debug("이력 조회 테스트 데이터 준비 완료: productId={}, otherProductId={}", productId, otherProductId);

        Page<StockHistoryResponse> page = stockHistoryRepository.findHistories(productId, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(StockHistoryResponse::requestId)
                .containsExactly(requestIds.get(2), requestIds.get(1), requestIds.get(0));
        assertThat(page.getTotalElements()).isEqualTo(3);
        log.info("상품별 id 역순 이력 조회 확인: productId={}", productId);
    }

    @Test
    void 요청의_정렬_조건은_무시하고_id_역순으로_조회한다() {
        Long productId = insertProduct(jdbcTemplate, "SKUQD3", "상품 3", 100L);
        List<String> requestIds = insertHistories(productId, 3);

        Page<StockHistoryResponse> page = stockHistoryRepository.findHistories(
                productId, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "createdAt")));

        assertThat(page.getContent()).extracting(StockHistoryResponse::requestId)
                .containsExactly(requestIds.get(2), requestIds.get(1), requestIds.get(0));
        log.info("요청 정렬 무시 확인: productId={}", productId);
    }

    @Test
    void 페이지_단위로_조회하고_전체_건수를_반환한다() {
        Long productId = insertProduct(jdbcTemplate, "SKUQD4", "상품 4", 100L);
        List<String> requestIds = insertHistories(productId, 5);

        Page<StockHistoryResponse> page = stockHistoryRepository.findHistories(productId, PageRequest.of(1, 2));

        assertThat(page.getContent()).extracting(StockHistoryResponse::requestId)
                .containsExactly(requestIds.get(2), requestIds.get(1));
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        log.info("이력 페이징 확인: productId={}, total={}", productId, page.getTotalElements());
    }

    @Test
    void 조회_건수와_무관하게_목록과_건수_쿼리_두_번만_실행된다() {
        Long productId = insertProduct(jdbcTemplate, "SKUQD5", "상품 5", 100L);
        insertHistories(productId, 5);
        statistics.clear();

        Page<StockHistoryResponse> page = stockHistoryRepository.findHistories(productId, PageRequest.of(0, 3));

        assertThat(page.getContent()).hasSize(3);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        log.info("이력 조회 쿼리 수 확인: statements={}", statistics.getPrepareStatementCount());
    }

    @Test
    void 결과가_페이지_크기보다_적으면_건수_쿼리를_생략한다() {
        Long productId = insertProduct(jdbcTemplate, "SKUQD6", "상품 6", 100L);
        insertHistories(productId, 2);
        statistics.clear();

        Page<StockHistoryResponse> page = stockHistoryRepository.findHistories(productId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        log.info("건수 쿼리 생략 확인: statements={}", statistics.getPrepareStatementCount());
    }

    /** Inserts with created_at deliberately reversed relative to id order, so only id ordering passes. */
    private List<String> insertHistories(Long productId, int count) {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<String> requestIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String requestId = newRequestId();
            requestIds.add(requestId);
            jdbcTemplate.update("""
                            INSERT INTO stock_history (product_id, type, quantity, before_quantity, after_quantity, request_id, created_at)
                            VALUES (?, 'INBOUND', 10, ?, ?, ?, ?)
                            """,
                    productId, 100L + i * 10, 110L + i * 10, requestId,
                    Timestamp.from(base.minusSeconds(i * 60L)));
        }
        return requestIds;
    }
}
