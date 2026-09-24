package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.InventoryException;
import com.example.inventory.management.inventory.product.Product;
import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.stock.dto.InboundRequest;
import com.example.inventory.management.inventory.stock.dto.OutboundRequest;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.example.inventory.management.inventory.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.inventory.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the atomic conditional UPDATE (WHERE quantity >= :quantity), the per-requestId
 * advisory lock and the product_code registration rule against a real Postgres, under genuine
 * concurrent transactions — each thread calls through StockService, so each request runs in its
 * own connection/transaction via StockMutationExecutor, not a shared one.
 */
class StockServiceConcurrencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockServiceConcurrencyTest.class);

    private static final String SUCCESS = "SUCCESS";
    private static final int ROUNDS = 20;
    private static final int SAME_KEY_THREADS = 5;
    private static final int SAME_CODE_THREADS = 8;

    @Autowired
    private StockService stockService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 요청량과_재고가_정확히_일치하면_동시_출고에도_유실_없이_전량_성공한다() throws InterruptedException {
        Seeded seeded = seedProduct(100L);
        log.debug("동시성 테스트(정확히 일치) 상품 준비 완료: productId={}, initialQuantity=100", seeded.id());

        List<String> results = runConcurrently(10,
                i -> outcome(() -> stockService.outbound(new OutboundRequest(seeded.id(), seeded.code(), 10L, newRequestId()))));

        assertThat(countOf(results, SUCCESS)).isEqualTo(10);
        assertThat(quantityOf(seeded.id())).isZero();
        assertThat(historyCount(seeded.id())).isEqualTo(11); // 1 inbound seed + 10 successful outbound, no duplicates
        log.info("동시 출고 10건 전량 성공, 최종 재고 0 확인: productId={}, results={}", seeded.id(), tally(results));
    }

    @Test
    void 요청량이_재고를_초과하면_동시_출고시_재고만큼만_성공한다() throws InterruptedException {
        Seeded seeded = seedProduct(100L);
        log.debug("동시성 테스트(재고 초과) 상품 준비 완료: productId={}, initialQuantity=100", seeded.id());

        List<String> results = runConcurrently(20,
                i -> outcome(() -> stockService.outbound(new OutboundRequest(seeded.id(), seeded.code(), 10L, newRequestId()))));

        assertThat(countOf(results, SUCCESS)).isEqualTo(10);
        assertThat(countOf(results, "INSUFFICIENT_STOCK")).isEqualTo(10);
        assertThat(quantityOf(seeded.id())).isZero();
        assertThat(historyCount(seeded.id())).isEqualTo(11); // 1 inbound seed + only the 10 that actually succeeded
        log.info("동시 출고 20건 중 재고만큼만 성공 확인: productId={}, results={}", seeded.id(), tally(results));
    }

    @Test
    void 재고와_같은_수량을_같은_requestId로_동시_출고하면_한_건만_성공하고_나머지는_중복_요청으로_거절된다()
            throws InterruptedException {
        assertSameKeyOutboundAppliedOnce(10L, 10L);
    }

    @Test
    void 재고가_요청량의_1_5배일때_같은_requestId로_동시_출고하면_한_건만_성공하고_나머지는_중복_요청으로_거절된다()
            throws InterruptedException {
        assertSameKeyOutboundAppliedOnce(15L, 10L);
    }

    @Test
    void 기존_상품에_같은_requestId로_동시_입고하면_한_건만_성공하고_나머지는_중복_요청으로_거절된다()
            throws InterruptedException {
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            Seeded seeded = seedProduct(10L);
            String requestId = newRequestId();

            List<String> results = runConcurrently(SAME_KEY_THREADS,
                    i -> outcome(() -> stockService.inbound(new InboundRequest(seeded.id(), seeded.code(), null, 5L, requestId))));

            assertThat(countOf(results, SUCCESS)).as("round %d: %s", round, results).isEqualTo(1);
            assertThat(countOf(results, "DUPLICATE_REQUEST")).as("round %d: %s", round, results).isEqualTo(SAME_KEY_THREADS - 1);
            assertThat(quantityOf(seeded.id())).isEqualTo(15L);
            assertThat(historyCount(seeded.id())).isEqualTo(2); // seed + exactly one inbound
            assertThat(requestIdRowCount(requestId)).isEqualTo(1);
            accumulate(total, results);
        }
        log.info("기존 상품 동일 requestId 동시 입고 {}라운드 결과: {}", ROUNDS, total);
    }

    @Test
    void 신규_상품을_같은_requestId로_동시_입고하면_한_건만_성공하고_나머지는_중복_요청으로_거절된다()
            throws InterruptedException {
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            String productCode = uniqueCode("DUPNEW");
            String requestId = newRequestId();

            List<String> results = runConcurrently(SAME_KEY_THREADS,
                    i -> outcome(() -> stockService.inbound(new InboundRequest(null, productCode, "신규 상품", 7L, requestId))));

            assertThat(countOf(results, SUCCESS)).as("round %d: %s", round, results).isEqualTo(1);
            // Duplicate requestId is checked before the product_code rule, so the losers must see
            // DUPLICATE_REQUEST, never PRODUCT_CODE_ALREADY_EXISTS.
            assertThat(countOf(results, "DUPLICATE_REQUEST")).as("round %d: %s", round, results).isEqualTo(SAME_KEY_THREADS - 1);
            Product product = productRepository.findByProductCode(productCode).orElseThrow();
            assertThat(product.getQuantity()).isEqualTo(7L);
            assertThat(historyCount(product.getId())).isEqualTo(1);
            assertThat(requestIdRowCount(requestId)).isEqualTo(1);
            accumulate(total, results);
        }
        log.info("신규 상품 동일 requestId 동시 입고 {}라운드 결과: {}", ROUNDS, total);
    }

    @Test
    void 같은_신규_상품코드를_서로_다른_requestId로_동시_등록하면_한_건만_성공하고_나머지는_상품코드_중복으로_거절된다()
            throws InterruptedException {
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            String productCode = uniqueCode("RACE");

            List<String> results = runConcurrently(SAME_CODE_THREADS,
                    i -> outcome(() -> stockService.inbound(new InboundRequest(null, productCode, "경쟁 상품", 3L, newRequestId()))));

            assertThat(countOf(results, SUCCESS)).as("round %d: %s", round, results).isEqualTo(1);
            assertThat(countOf(results, "PRODUCT_CODE_ALREADY_EXISTS")).as("round %d: %s", round, results)
                    .isEqualTo(SAME_CODE_THREADS - 1);
            Product product = productRepository.findByProductCode(productCode).orElseThrow();
            assertThat(product.getQuantity()).isEqualTo(3L); // never accumulated onto the winner's row
            assertThat(historyCount(product.getId())).isEqualTo(1);
            accumulate(total, results);
        }
        log.info("동일 신규 상품코드 동시 등록 {}라운드 결과: {}", ROUNDS, total);
    }

    private void assertSameKeyOutboundAppliedOnce(long initialQuantity, long quantity) throws InterruptedException {
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            Seeded seeded = seedProduct(initialQuantity);
            String requestId = newRequestId();

            List<String> results = runConcurrently(SAME_KEY_THREADS,
                    i -> outcome(() -> stockService.outbound(new OutboundRequest(seeded.id(), seeded.code(), quantity, requestId))));

            assertThat(countOf(results, SUCCESS)).as("round %d: %s", round, results).isEqualTo(1);
            assertThat(countOf(results, "DUPLICATE_REQUEST")).as("round %d: %s", round, results).isEqualTo(SAME_KEY_THREADS - 1);
            assertThat(quantityOf(seeded.id())).isEqualTo(initialQuantity - quantity);
            assertThat(historyCount(seeded.id())).isEqualTo(2); // seed + exactly one outbound
            assertThat(requestIdRowCount(requestId)).isEqualTo(1);
            accumulate(total, results);
        }
        log.info("동일 requestId 동시 출고 {}라운드 결과(재고={}, 요청량={}): {}", ROUNDS, initialQuantity, quantity, total);
    }

    private record Seeded(Long id, String code) {
    }

    private Seeded seedProduct(long initialQuantity) {
        String productCode = uniqueCode("CONC");
        Long productId = stockService.inbound(
                new InboundRequest(null, productCode, "동시성 테스트 상품", initialQuantity, newRequestId())).productId();
        return new Seeded(productId, productCode);
    }

    private String outcome(Runnable call) {
        try {
            call.run();
            return SUCCESS;
        } catch (InventoryException e) {
            log.warn("동시 요청 거절(예상 가능한 결과): code={}", e.getErrorCode());
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            log.error("동시 요청 중 예상하지 못한 예외 발생", e);
            return e.getClass().getSimpleName();
        }
    }

    private long quantityOf(Long productId) {
        return productRepository.findById(productId).map(Product::getQuantity).orElseThrow();
    }

    private long historyCount(Long productId) {
        return stockHistoryRepository.findByProductId(productId, Pageable.unpaged()).getTotalElements();
    }

    private long requestIdRowCount(String requestId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stock_history WHERE request_id = ?", Long.class, requestId);
        return count == null ? 0 : count;
    }

    private static long countOf(List<String> results, String outcome) {
        return results.stream().filter(outcome::equals).count();
    }

    private static Map<String, Long> tally(List<String> results) {
        return results.stream().collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
    }

    private static void accumulate(Map<String, Long> total, List<String> results) {
        tally(results).forEach((key, count) -> total.merge(key, count, Long::sum));
    }

    private List<String> runConcurrently(int threadCount, IntFunction<String> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<String>> futures = IntStream.range(0, threadCount)
                    .mapToObj(i -> executor.submit(() -> {
                        startGate.await();
                        return task.apply(i);
                    }))
                    .collect(Collectors.toList());

            startGate.countDown();

            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        } finally {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
