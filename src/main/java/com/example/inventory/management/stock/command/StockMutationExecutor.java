package com.example.inventory.management.stock.command;

import com.example.inventory.management.common.exception.DuplicateRequestException;
import com.example.inventory.management.common.exception.InsufficientStockException;
import com.example.inventory.management.common.exception.ProductCodeAlreadyExistsException;
import com.example.inventory.management.common.exception.ProductCodeMismatchException;
import com.example.inventory.management.common.exception.ProductNotFoundException;
import com.example.inventory.management.common.exception.SqlStates;
import com.example.inventory.management.common.exception.StockQuantityOverflowException;
import com.example.inventory.management.product.domain.Product;
import com.example.inventory.management.product.domain.ProductRepository;
import com.example.inventory.management.stock.command.dto.InboundRequest;
import com.example.inventory.management.stock.command.dto.OutboundRequest;
import com.example.inventory.management.stock.command.dto.StockChangeResponse;
import com.example.inventory.management.stock.domain.StockHistory;
import com.example.inventory.management.stock.domain.StockHistoryRepository;
import com.example.inventory.management.stock.domain.StockType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.Supplier;

/**
 * Holds the actual stock-mutating transactions, kept in a separate bean (rather than as methods on
 * StockCommandService) so that StockCommandService sits outside the transactional proxy: a constraint violation
 * surfaces there only after this transaction has rolled back, and can be translated to a 409.
 *
 * <p>Each mutation first claims its requestId: it takes a transaction-scoped advisory lock on the
 * key and then checks, inside the transaction, whether a history row with that key already exists.
 * Concurrent requests with the same key therefore run one after another, and every one after the
 * first successful commit is rejected with DUPLICATE_REQUEST before any business check. Lock order
 * is always advisory lock → product row, so the two locks cannot deadlock.
 *
 * <p>Each mutation reads the application clock once ({@link #now()}) and uses that value for the
 * product's updated_at (and created_at for a new product), the history's created_at and the
 * response, so all of them agree exactly.
 *
 * <p>Business failures are thrown, not logged, here: GlobalExceptionHandler logs each failed
 * request exactly once, so the exception message carries the context needed to diagnose it.
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
        Instant now = now();

        Long productId;
        String productCode;
        Long beforeQuantity;
        Long afterQuantity;

        if (request.productId() != null) {
            Product product = findMatchingProduct(request.productId(), request.productCode());
            afterQuantity = translateOverflow("productId=" + product.getId(),
                    () -> productRepository.increaseQuantity(product.getId(), request.quantity(), now))
                    .orElseThrow(() -> new ProductNotFoundException(product.getId()));
            beforeQuantity = afterQuantity - request.quantity();
            productId = product.getId();
            productCode = product.getProductCode();
            log.debug("기존 상품 입고: productId={}, beforeQuantity={}, afterQuantity={}",
                    productId, beforeQuantity, afterQuantity);
        } else {
            ProductRepository.InsertedProduct inserted = productRepository
                    .insertProductIfAbsent(request.productCode(), request.productName(), request.quantity(), now)
                    .orElseThrow(() -> new ProductCodeAlreadyExistsException(request.productCode()));
            productId = inserted.getId();
            afterQuantity = inserted.getQuantity();
            beforeQuantity = 0L;
            productCode = request.productCode();
            log.debug("신규 상품 등록 입고: productId={}, productCode={}, afterQuantity={}",
                    productId, productCode, afterQuantity);
        }

        StockHistory history = new StockHistory(
                productId, StockType.INBOUND, request.quantity(), beforeQuantity, afterQuantity, request.requestId(), now);
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

        Product product = findMatchingProduct(request.productId(), request.productCode());
        Instant now = now();

        Long afterQuantity = productRepository.decreaseQuantityIfSufficient(product.getId(), request.quantity(), now)
                .orElseThrow(() -> new InsufficientStockException(product.getId(), request.quantity()));
        Long beforeQuantity = afterQuantity + request.quantity();
        log.debug("출고 수량 차감 완료: productId={}, beforeQuantity={}, afterQuantity={}",
                product.getId(), beforeQuantity, afterQuantity);

        StockHistory history = new StockHistory(
                product.getId(), StockType.OUTBOUND, request.quantity(), beforeQuantity, afterQuantity, request.requestId(),
                now);
        stockHistoryRepository.save(history);
        log.debug("출고 이력 저장 완료: historyId={}, requestId={}", history.getId(), request.requestId());

        log.info("출고 완료: productId={}, productCode={}, quantity={}, beforeQuantity={}, afterQuantity={}, requestId={}",
                product.getId(), product.getProductCode(), request.quantity(), beforeQuantity, afterQuantity, request.requestId());

        return new StockChangeResponse(product.getId(), product.getProductCode(), StockType.OUTBOUND, request.quantity(),
                beforeQuantity, afterQuantity, request.requestId(), history.getCreatedAt());
    }

    /**
     * Truncated to microseconds, the precision of Postgres TIMESTAMPTZ, so the value returned in the
     * response is exactly the value stored.
     */
    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Takes the requestId advisory lock, then re-checks for a committed request with the same key.
     * The check runs after the lock is granted, so under READ COMMITTED it sees a history row
     * committed by the previous holder of the lock.
     */
    private void claimRequestId(String requestId) {
        stockHistoryRepository.acquireRequestIdLock(requestId);
        if (stockHistoryRepository.existsByRequestId(requestId)) {
            throw new DuplicateRequestException(requestId);
        }
        log.debug("requestId 선점 완료: requestId={}", requestId);
    }

    /** 404 if the product doesn't exist, 400 if productCode is not exactly that product's code. */
    private Product findMatchingProduct(Long productId, String productCode) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
        if (!product.getProductCode().equals(productCode)) {
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
                throw new StockQuantityOverflowException(target);
            }
            throw e;
        }
    }
}
