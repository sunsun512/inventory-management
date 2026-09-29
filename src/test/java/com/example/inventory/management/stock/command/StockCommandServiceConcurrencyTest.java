package com.example.inventory.management.stock.command;

import com.example.inventory.management.common.exception.InventoryException;
import com.example.inventory.management.product.domain.Product;
import com.example.inventory.management.product.domain.ProductRepository;
import com.example.inventory.management.stock.command.dto.InboundRequest;
import com.example.inventory.management.stock.command.dto.OutboundRequest;
import com.example.inventory.management.stock.domain.StockHistory;
import com.example.inventory.management.stock.domain.StockHistoryRepository;
import com.example.inventory.management.stock.domain.StockType;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the atomic conditional UPDATE (WHERE quantity >= :quantity), the per-requestId
 * advisory lock and the product_code registration rule against a real Postgres, under genuine
 * concurrent transactions — each thread calls through StockCommandService, so each request runs in its
 * own connection/transaction via StockMutationExecutor, not a shared one.
 */
class StockCommandServiceConcurrencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockCommandServiceConcurrencyTest.class);

    private static final String SUCCESS = "SUCCESS";
    private static final int ROUNDS = 20;
    private static final int SAME_KEY_THREADS = 5;
    private static final int SAME_CODE_THREADS = 8;

    @Autowired
    private StockCommandService stockCommandService;

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
                i -> outcome(() -> stockCommandService.outbound(new OutboundRequest(seeded.id(), seeded.code(), 10L, newRequestId()))));

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
                i -> outcome(() -> stockCommandService.outbound(new OutboundRequest(seeded.id(), seeded.code(), 10L, newRequestId()))));

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
                    i -> outcome(() -> stockCommandService.inbound(new InboundRequest(seeded.id(), seeded.code(), null, 5L, requestId))));

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
                    i -> outcome(() -> stockCommandService.inbound(new InboundRequest(null, productCode, "신규 상품", 7L, requestId))));

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
                    i -> outcome(() -> stockCommandService.inbound(new InboundRequest(null, productCode, "경쟁 상품", 3L, newRequestId()))));

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

    @Test
    void 입고와_출고를_동시에_섞어_처리해도_최종_재고와_모든_이력이_정합하다() throws InterruptedException {
        int pairs = 10;
        long initial = 5_000L;
        Seeded seeded = seedProduct(initial);
        long inboundSum = IntStream.range(0, pairs).mapToLong(i -> 100L + i).sum();
        long outboundSum = IntStream.range(0, pairs).mapToLong(i -> 50L + i).sum();

        List<String> results = runConcurrently(pairs * 2, i -> {
            int n = i / 2;
            return i % 2 == 0
                    ? outcome(() -> stockCommandService.inbound(
                            new InboundRequest(seeded.id(), seeded.code(), null, 100L + n, newRequestId())))
                    : outcome(() -> stockCommandService.outbound(
                            new OutboundRequest(seeded.id(), seeded.code(), 50L + n, newRequestId())));
        });

        assertThat(countOf(results, SUCCESS)).as("results: %s", tally(results)).isEqualTo(pairs * 2);
        assertThat(quantityOf(seeded.id())).isEqualTo(initial + inboundSum - outboundSum);
        List<StockHistory> histories = historiesOf(seeded.id());
        assertThat(histories).hasSize(1 + pairs * 2); // seed + every request, no duplicates
        assertHistoryArithmetic(histories);
        log.info("입출고 혼합 동시 처리 정합성 확인: productId={}, finalQuantity={}", seeded.id(), quantityOf(seeded.id()));
    }

    @Test
    void 기존_상품에_서로_다른_requestId로_동시_입고하면_수량이_모두_합산된다() throws InterruptedException {
        int threads = 10;
        long initial = 10L;
        long each = 7L;
        Seeded seeded = seedProduct(initial);

        List<String> results = runConcurrently(threads,
                i -> outcome(() -> stockCommandService.inbound(
                        new InboundRequest(seeded.id(), seeded.code(), null, each, newRequestId()))));

        assertThat(countOf(results, SUCCESS)).as("results: %s", tally(results)).isEqualTo(threads);
        assertThat(quantityOf(seeded.id())).isEqualTo(initial + threads * each);
        List<StockHistory> histories = historiesOf(seeded.id());
        assertThat(histories).hasSize(1 + threads);
        assertHistoryArithmetic(histories);
        // Row-locked updates are serialized, so no two inbounds may observe the same before/after.
        assertThat(histories.stream().map(StockHistory::getAfterQuantity).distinct().count()).isEqualTo(1 + threads);
        log.info("서로 다른 requestId 동시 입고 합산 확인: productId={}, finalQuantity={}", seeded.id(), quantityOf(seeded.id()));
    }

    @Test
    void 같은_requestId로_입고와_출고를_동시에_보내면_한_건만_반영되고_나머지는_중복_요청으로_거절된다()
            throws InterruptedException {
        int threads = 6;
        long initial = 100L;
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            Seeded seeded = seedProduct(initial);
            String requestId = newRequestId();

            List<String> results = runConcurrently(threads, i -> i % 2 == 0
                    ? outcome(() -> stockCommandService.inbound(new InboundRequest(seeded.id(), seeded.code(), null, 10L, requestId)))
                    : outcome(() -> stockCommandService.outbound(new OutboundRequest(seeded.id(), seeded.code(), 10L, requestId))));

            assertThat(countOf(results, SUCCESS)).as("round %d: %s", round, results).isEqualTo(1);
            assertThat(countOf(results, "DUPLICATE_REQUEST")).as("round %d: %s", round, results).isEqualTo(threads - 1);
            String winnerType = jdbcTemplate.queryForObject(
                    "SELECT type FROM stock_history WHERE request_id = ?", String.class, requestId);
            long expected = StockType.INBOUND.name().equals(winnerType) ? initial + 10L : initial - 10L;
            assertThat(quantityOf(seeded.id())).as("round %d: winner=%s", round, winnerType).isEqualTo(expected);
            assertThat(historyCount(seeded.id())).isEqualTo(2); // seed + exactly one change
            assertThat(requestIdRowCount(requestId)).isEqualTo(1);
            accumulate(total, results);
        }
        log.info("동일 requestId 입고/출고 동시 요청 {}라운드 결과: {}", ROUNDS, total);
    }

    private static void assertHistoryArithmetic(List<StockHistory> histories) {
        for (StockHistory h : histories) {
            long expectedAfter = h.getType() == StockType.INBOUND
                    ? h.getBeforeQuantity() + h.getQuantity()
                    : h.getBeforeQuantity() - h.getQuantity();
            assertThat(h.getAfterQuantity())
                    .as("history %d (%s): before=%d, qty=%d", h.getId(), h.getType(), h.getBeforeQuantity(), h.getQuantity())
                    .isEqualTo(expectedAfter);
        }
    }

    private void assertSameKeyOutboundAppliedOnce(long initialQuantity, long quantity) throws InterruptedException {
        Map<String, Long> total = new TreeMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            Seeded seeded = seedProduct(initialQuantity);
            String requestId = newRequestId();

            List<String> results = runConcurrently(SAME_KEY_THREADS,
                    i -> outcome(() -> stockCommandService.outbound(new OutboundRequest(seeded.id(), seeded.code(), quantity, requestId))));

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
        Long productId = stockCommandService.inbound(
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
        return historiesOf(productId).size();
    }

    private List<StockHistory> historiesOf(Long productId) {
        return stockHistoryRepository.findAll().stream()
                .filter(history -> productId.equals(history.getProductId()))
                .toList();
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
