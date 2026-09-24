package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.InsufficientStockException;
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

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the atomic conditional UPDATE (WHERE quantity >= :quantity) against a real
 * Postgres so the DB actually enforces the invariant under genuine concurrent transactions —
 * each thread calls through StockService, so each outbound() runs in its own connection/
 * transaction via StockMutationExecutor, not a shared one.
 */
class StockServiceConcurrencyTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockServiceConcurrencyTest.class);

    @Autowired
    private StockService stockService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Test
    void 요청량과_재고가_정확히_일치하면_동시_출고에도_유실_없이_전량_성공한다() throws InterruptedException {
        Long productId = seedProduct(100L);
        log.debug("동시성 테스트(정확히 일치) 상품 준비 완료: productId={}, initialQuantity=100", productId);

        List<Boolean> results = runConcurrently(10, i -> attemptOutbound(productId, 10L, "exact-" + i));

        long succeeded = results.stream().filter(Boolean::booleanValue).count();
        assertThat(succeeded).isEqualTo(10);

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getQuantity()).isZero();

        long historyCount = stockHistoryRepository.findByProductId(productId, Pageable.unpaged()).getTotalElements();
        assertThat(historyCount).isEqualTo(11); // 1 inbound seed + 10 successful outbound, no duplicates
        log.info("동시 출고 10건 전량 성공, 최종 재고 0 확인: productId={}, succeeded={}, historyCount={}",
                productId, succeeded, historyCount);
    }

    @Test
    void 요청량이_재고를_초과하면_동시_출고시_재고만큼만_성공한다() throws InterruptedException {
        Long productId = seedProduct(100L);
        log.debug("동시성 테스트(재고 초과) 상품 준비 완료: productId={}, initialQuantity=100", productId);

        List<Boolean> results = runConcurrently(20, i -> attemptOutbound(productId, 10L, "over-" + i));

        long succeeded = results.stream().filter(Boolean::booleanValue).count();
        long failed = results.size() - succeeded;
        assertThat(succeeded).isEqualTo(10);
        assertThat(failed).isEqualTo(10);

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getQuantity()).isZero();

        long historyCount = stockHistoryRepository.findByProductId(productId, Pageable.unpaged()).getTotalElements();
        assertThat(historyCount).isEqualTo(11); // 1 inbound seed + only the 10 that actually succeeded
        log.info("동시 출고 20건 중 재고만큼만 성공 확인: productId={}, succeeded={}, failed={}, historyCount={}",
                productId, succeeded, failed, historyCount);
    }

    private Long seedProduct(long initialQuantity) {
        String sku = "CONC" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
        return stockService.inbound(new InboundRequest(null, sku, "동시성 테스트 상품", initialQuantity, "seed-" + sku))
                .productId();
    }

    private boolean attemptOutbound(Long productId, long quantity, String requestId) {
        try {
            stockService.outbound(new OutboundRequest(productId, quantity, requestId));
            return true;
        } catch (InsufficientStockException e) {
            log.error("동시 출고 중 재고 부족으로 실패(예상된 결과): productId={}, requestId={}", productId, requestId);
            return false;
        }
    }

    private List<Boolean> runConcurrently(int threadCount, IntFunction<Boolean> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = IntStream.range(0, threadCount)
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
