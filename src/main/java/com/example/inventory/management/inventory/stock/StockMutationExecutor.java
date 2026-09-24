package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.DuplicateRequestException;
import com.example.inventory.management.inventory.common.exception.InsufficientStockException;
import com.example.inventory.management.inventory.common.exception.ProductCodeAlreadyExistsException;
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
 * Holds the actual stock-mutating transactions, kept in a separate bean (rather than as methods on
 * StockService) so that StockService sits outside the transactional proxy: a constraint violation
 * surfaces there only after this transaction has rolled back, and can be translated to a 409.
 *
 * <p>Each mutation first claims its requestId: it takes a transaction-scoped advisory lock on the
 * key and then checks, inside the transaction, whether a history row with that key already exists.
 * Concurrent requests with the same key therefore run one after another, and every one after the
 * first successful commit is rejected with DUPLICATE_REQUEST before any business check. Lock order
 * is always advisory lock → product row, so the two locks cannot deadlock.
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
        claimRequestId(request.requestId());

        Long productId;
        String productCode;
        Long beforeQuantity;
        Long afterQuantity;

        if (request.productId() != null) {
            Product product = findMatchingProduct(request.productId(), request.productCode(), "입고");
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
            ProductRepository.InsertedProduct inserted = productRepository
                    .insertProductIfAbsent(request.productCode(), request.productName(), request.quantity())
                    .orElseThrow(() -> {
                        log.error("입고 실패 - 이미 등록된 상품코드로 신규 등록 시도: productCode={}", request.productCode());
                        return new ProductCodeAlreadyExistsException(request.productCode());
                    });
            productId = inserted.getId();
            afterQuantity = inserted.getQuantity();
            beforeQuantity = 0L;
            productCode = request.productCode();
            log.debug("신규 상품 등록 입고: productId={}, productCode={}, afterQuantity={}",
                    productId, productCode, afterQuantity);
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

    @Transactional
    StockChangeResponse applyOutbound(OutboundRequest request) {
        log.debug("출고 처리 시작: productId={}, productCode={}, quantity={}, requestId={}",
                request.productId(), request.productCode(), request.quantity(), request.requestId());
        claimRequestId(request.requestId());

        Product product = findMatchingProduct(request.productId(), request.productCode(), "출고");

        Long afterQuantity = productRepository.decreaseQuantityIfSufficient(product.getId(), request.quantity())
                .orElseThrow(() -> {
                    log.error("출고 실패 - 재고 부족: productId={}, 요청수량={}", product.getId(), request.quantity());
                    return new InsufficientStockException(product.getId());
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

    /**
     * Takes the requestId advisory lock, then re-checks for a committed request with the same key.
     * The check runs after the lock is granted, so under READ COMMITTED it sees a history row
     * committed by the previous holder of the lock.
     */
    private void claimRequestId(String requestId) {
        stockHistoryRepository.acquireRequestIdLock(requestId);
        if (stockHistoryRepository.existsByRequestId(requestId)) {
            log.error("재고 변경 거절 - 이미 처리된 requestId: requestId={}", requestId);
            throw new DuplicateRequestException(requestId);
        }
        log.debug("requestId 선점 완료: requestId={}", requestId);
    }

    /** 404 if the product doesn't exist, 400 if productCode is not exactly that product's code. */
    private Product findMatchingProduct(Long productId, String productCode, String operation) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> {
                    log.error("{} 실패 - 존재하지 않는 productId={}", operation, productId);
                    return new ProductNotFoundException(productId);
                });
        if (!product.getProductCode().equals(productCode)) {
            log.error("{} 실패 - productId와 productCode 불일치: productId={}, 상품코드={}, 요청코드={}",
                    operation, product.getId(), product.getProductCode(), productCode);
            throw new ProductCodeMismatchException(product.getId(), productCode);
        }
        return product;
    }

    /**
     * The only way a positive, limit-checked increment can fail with 22003 is the stored total
     * exceeding BIGINT — a business conflict (409), not an internal error.
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
}
