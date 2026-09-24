package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.InsufficientStockException;
import com.example.inventory.management.inventory.common.exception.ProductCodeMismatchException;
import com.example.inventory.management.inventory.common.exception.ProductNotFoundException;
import com.example.inventory.management.inventory.common.exception.SqlStates;
import com.example.inventory.management.inventory.common.exception.StockQuantityOverflowException;
import com.example.inventory.management.inventory.product.Product;
import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.stock.dto.InboundRequest;
import com.example.inventory.management.inventory.stock.dto.OutboundRequest;
import com.example.inventory.management.inventory.stock.dto.StockChangeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Holds the actual stock-mutating transactions, kept in a separate bean (rather than as
 * methods on StockService) so that StockService can catch a DataIntegrityViolationException
 * from a duplicate request_id and safely re-query afterwards: Postgres aborts the whole
 * transaction once a statement fails, so the caller must be outside that transaction's proxy
 * boundary before it can run another query — that's only possible with a genuine cross-bean
 * call through Spring's transactional proxy.
 */
@Component
class StockMutationExecutor {

    private static final Logger log = LoggerFactory.getLogger(StockMutationExecutor.class);

    private final ProductRepository productRepository;
    private final StockHistoryRepository stockHistoryRepository;

    StockMutationExecutor(ProductRepository productRepository, StockHistoryRepository stockHistoryRepository) {
        this.productRepository = productRepository;
        this.stockHistoryRepository = stockHistoryRepository;
    }

    @Transactional
    StockChangeResponse applyInbound(InboundRequest request) {
        log.debug("입고 처리 시작: productId={}, productCode={}, quantity={}, requestId={}",
                request.productId(), request.productCode(), request.quantity(), request.requestId());

        Long productId;
        String productCode;
        Long beforeQuantity;
        Long afterQuantity;

        if (request.productId() != null) {
            Product product = productRepository.findById(request.productId())
                    .orElseThrow(() -> {
                        log.error("입고 실패 - 존재하지 않는 productId={}", request.productId());
                        return new ProductNotFoundException(request.productId());
                    });
            if (request.productCode() != null && !request.productCode().equals(product.getProductCode())) {
                log.error("입고 실패 - productId와 productCode 불일치: productId={}, 상품코드={}, 요청코드={}",
                        product.getId(), product.getProductCode(), request.productCode());
                throw new ProductCodeMismatchException(product.getId(), request.productCode());
            }
            afterQuantity = translateOverflow("productId=" + product.getId(),
                    () -> productRepository.increaseQuantity(product.getId(), request.quantity()))
                    .orElseThrow(() -> {
                        log.error("입고 실패 - 수량 증가 쿼리 대상 없음: productId={}", product.getId());
                        return new ProductNotFoundException(product.getId());
                    });
            beforeQuantity = afterQuantity - request.quantity();
            productId = product.getId();
            productCode = product.getProductCode();
            log.debug("기존 상품 입고: productId={}, beforeQuantity={}, afterQuantity={}",
                    productId, beforeQuantity, afterQuantity);
        } else {
            ProductRepository.UpsertResult result = translateOverflow("productCode=" + request.productCode(),
                    () -> productRepository.upsertProductStock(
                            request.productCode(), request.productName(), request.quantity()));
            productId = result.getId();
            afterQuantity = result.getQuantity();
            beforeQuantity = Boolean.TRUE.equals(result.getInserted()) ? 0L : afterQuantity - request.quantity();
            productCode = request.productCode();
            log.debug("신규/업서트 입고: productId={}, productCode={}, inserted={}, afterQuantity={}",
                    productId, productCode, result.getInserted(), afterQuantity);
        }

        StockHistory history = new StockHistory(
                productId, StockType.INBOUND, request.quantity(), beforeQuantity, afterQuantity, request.requestId());
        stockHistoryRepository.save(history);
        log.debug("입고 이력 저장 완료: historyId={}, requestId={}", history.getId(), request.requestId());

        log.info("입고 완료: productId={}, productCode={}, quantity={}, beforeQuantity={}, afterQuantity={}, requestId={}",
                productId, productCode, request.quantity(), beforeQuantity, afterQuantity, request.requestId());

        return new StockChangeResponse(productId, productCode, StockType.INBOUND, request.quantity(),
                beforeQuantity, afterQuantity, request.requestId(), history.getCreatedAt());
    }

    /**
     * The only way a positive, limit-checked increment can fail with 22003 is the stored total
     * exceeding BIGINT — a business conflict (409), not an internal error, and it must not be
     * mistaken for a request_id collision by StockService.applyOrRecover.
     */
    private <T> T translateOverflow(String target, Supplier<T> increment) {
        try {
            return increment.get();
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.is(e, SqlStates.NUMERIC_VALUE_OUT_OF_RANGE)) {
                log.error("입고 실패 - 재고 수량 BIGINT 범위 초과: {}", target);
                throw new StockQuantityOverflowException(target);
            }
            throw e;
        }
    }

    @Transactional
    StockChangeResponse applyOutbound(OutboundRequest request) {
        log.debug("출고 처리 시작: productId={}, quantity={}, requestId={}",
                request.productId(), request.quantity(), request.requestId());

        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> {
                    log.error("출고 실패 - 존재하지 않는 productId={}", request.productId());
                    return new ProductNotFoundException(request.productId());
                });

        Long afterQuantity = productRepository.decreaseQuantityIfSufficient(request.productId(), request.quantity())
                .orElseThrow(() -> {
                    log.error("출고 실패 - 재고 부족: productId={}, 요청수량={}", request.productId(), request.quantity());
                    return new InsufficientStockException(request.productId());
                });
        Long beforeQuantity = afterQuantity + request.quantity();
        log.debug("출고 수량 차감 완료: productId={}, beforeQuantity={}, afterQuantity={}",
                product.getId(), beforeQuantity, afterQuantity);

        StockHistory history = new StockHistory(
                product.getId(), StockType.OUTBOUND, request.quantity(), beforeQuantity, afterQuantity, request.requestId());
        stockHistoryRepository.save(history);
        log.debug("출고 이력 저장 완료: historyId={}, requestId={}", history.getId(), request.requestId());

        log.info("출고 완료: productId={}, productCode={}, quantity={}, beforeQuantity={}, afterQuantity={}, requestId={}",
                product.getId(), product.getProductCode(), request.quantity(), beforeQuantity, afterQuantity, request.requestId());

        return new StockChangeResponse(product.getId(), product.getProductCode(), StockType.OUTBOUND, request.quantity(),
                beforeQuantity, afterQuantity, request.requestId(), history.getCreatedAt());
    }
}
