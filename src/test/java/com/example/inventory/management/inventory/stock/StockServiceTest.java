package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.InsufficientStockException;
import com.example.inventory.management.inventory.common.exception.ProductCodeMismatchException;
import com.example.inventory.management.inventory.common.exception.ProductNotFoundException;
import com.example.inventory.management.inventory.product.Product;
import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.stock.dto.InboundRequest;
import com.example.inventory.management.inventory.stock.dto.OutboundRequest;
import com.example.inventory.management.inventory.stock.dto.StockChangeResponse;
import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class StockServiceTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockServiceTest.class);

    @Autowired
    private StockService stockService;

    @Autowired
    private ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 기존_상품에_입고하면_수량이_증가한다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUA", "상품 A", 10L);
        log.debug("테스트 상품 준비 완료: productId={}, quantity=10", created.getId());

        StockChangeResponse response = stockService.inbound(
                new InboundRequest(created.getId(), null, null, 5L, "req-1"));

        assertThat(response.beforeQuantity()).isEqualTo(10L);
        assertThat(response.afterQuantity()).isEqualTo(15L);
        assertThat(response.type()).isEqualTo(StockType.INBOUND);
        log.info("입고 처리 결과 확인: productId={}, afterQuantity={}", response.productId(), response.afterQuantity());
    }

    @Test
    void 등록되지_않은_상품에_입고하면_신규_등록된다() {
        log.debug("신규 상품 자동 등록 테스트 시작: productCode=SKUNEW");
        StockChangeResponse response = stockService.inbound(
                new InboundRequest(null, "SKUNEW", "새 상품", 20L, "req-2"));

        assertThat(response.beforeQuantity()).isEqualTo(0L);
        assertThat(response.afterQuantity()).isEqualTo(20L);

        Product product = productRepository.findByProductCode("SKUNEW").orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(20L);
        log.info("신규 상품 자동 등록 확인: productId={}, quantity={}", product.getId(), product.getQuantity());
    }

    @Test
    void 존재하지_않는_상품을_출고하면_예외가_발생한다() {
        log.debug("존재하지 않는 상품 출고 테스트 시작: productId=999999");
        assertThatThrownBy(() -> stockService.outbound(new OutboundRequest(999_999L, 1L, "req-3")))
                .isInstanceOf(ProductNotFoundException.class);
        log.error("예상된 ProductNotFoundException 발생 확인: productId=999999");
    }

    @Test
    void 재고가_부족하면_예외가_발생하고_수량은_변하지_않는다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUB", "상품 B", 5L);
        log.debug("재고 부족 테스트 상품 준비 완료: productId={}, quantity=5", created.getId());

        assertThatThrownBy(() -> stockService.outbound(new OutboundRequest(created.getId(), 10L, "req-4")))
                .isInstanceOf(InsufficientStockException.class);
        log.error("예상된 InsufficientStockException 발생 확인: productId={}", created.getId());

        Product product = productRepository.findById(created.getId()).orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(5L);
    }

    @Test
    void 동일한_requestId로_재요청하면_재반영_없이_같은_결과를_반환한다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUC", "상품 C", 10L);
        InboundRequest request = new InboundRequest(created.getId(), null, null, 5L, "req-5");
        log.debug("멱등성 테스트 상품 준비 완료: productId={}, requestId=req-5", created.getId());

        StockChangeResponse first = stockService.inbound(request);
        StockChangeResponse second = stockService.inbound(request);

        assertThat(second).isEqualTo(first);
        log.info("동일 requestId 재요청시 동일 결과 반환 확인: requestId={}", request.requestId());

        // The native RETURNING update bypasses the persistence context, so within this single
        // test-managed transaction the entity loaded earlier (inside applyInbound) is stale in
        // the first-level cache; clear it to read the true post-update row. Production code
        // never hits this because each service call runs in its own short-lived transaction.
        entityManager.clear();
        Product product = productRepository.findById(created.getId()).orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(15L);
    }
    @Test
    void productId와_일치하는_상품코드를_함께_보내면_입고된다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUMATCH", "상품", 10L);
        log.debug("productId-상품코드 일치 테스트 상품 준비 완료: productId={}", created.getId());

        StockChangeResponse response = stockService.inbound(
                new InboundRequest(created.getId(), "SKUMATCH", null, 5L, "req-match-1"));

        assertThat(response.afterQuantity()).isEqualTo(15L);
        log.info("productId-상품코드 일치 입고 확인: productId={}, afterQuantity={}", created.getId(), response.afterQuantity());
    }

    @Test
    void productId와_상품코드가_일치하지_않으면_예외가_발생하고_수량은_변하지_않는다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUMINE", "상품", 10L);
        productRepository.upsertProductStock("SKUOTHER", "다른 상품", 10L);
        log.debug("productId-상품코드 불일치 테스트 상품 준비 완료: productId={}", created.getId());

        assertThatThrownBy(() -> stockService.inbound(
                new InboundRequest(created.getId(), "SKUOTHER", "다른 상품", 5L, "req-mismatch-1")))
                .isInstanceOf(ProductCodeMismatchException.class);
        log.error("예상된 ProductCodeMismatchException 발생 확인: productId={}", created.getId());

        entityManager.clear();
        assertThat(productRepository.findById(created.getId()).orElseThrow().getQuantity()).isEqualTo(10L);
        assertThat(productRepository.findByProductCode("SKUOTHER").orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 상품코드는_정규화_없이_정확히_일치해야_한다() {
        ProductRepository.UpsertResult created = productRepository.upsertProductStock("SKUEXACT", "상품", 10L);
        log.debug("상품코드 정확 일치 테스트 상품 준비 완료: productId={}", created.getId());

        assertThatThrownBy(() -> stockService.inbound(
                new InboundRequest(created.getId(), " skuexact", null, 5L, "req-mismatch-2")))
                .isInstanceOf(ProductCodeMismatchException.class);
        log.error("예상된 ProductCodeMismatchException 발생 확인 - 대소문자/공백 차이: productId={}", created.getId());

        entityManager.clear();
        assertThat(productRepository.findById(created.getId()).orElseThrow().getQuantity()).isEqualTo(10L);
    }

    @Test
    void 존재하지_않는_productId로_상품코드와_함께_입고하면_상품없음_예외가_발생한다() {
        log.debug("존재하지 않는 productId + 상품코드 입고 테스트 시작: productId=999999");
        assertThatThrownBy(() -> stockService.inbound(
                new InboundRequest(999_999L, "SKUANY", null, 5L, "req-mismatch-3")))
                .isInstanceOf(ProductNotFoundException.class);
        log.error("예상된 ProductNotFoundException 발생 확인: productId=999999");
    }
}
