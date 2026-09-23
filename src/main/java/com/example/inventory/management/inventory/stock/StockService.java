package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.ProductNotFoundException;
import com.example.inventory.management.inventory.product.Product;
import com.example.inventory.management.inventory.product.ProductRepository;
import com.example.inventory.management.inventory.product.ProductService;
import com.example.inventory.management.inventory.stock.dto.InboundRequest;
import com.example.inventory.management.inventory.stock.dto.OutboundRequest;
import com.example.inventory.management.inventory.stock.dto.StockChangeResponse;
import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductRepository productRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final ProductService productService;
    private final StockMutationExecutor mutationExecutor;

    public StockService(ProductRepository productRepository,
                         StockHistoryRepository stockHistoryRepository,
                         ProductService productService,
                         StockMutationExecutor mutationExecutor) {
        this.productRepository = productRepository;
        this.stockHistoryRepository = stockHistoryRepository;
        this.productService = productService;
        this.mutationExecutor = mutationExecutor;
    }

    public StockChangeResponse inbound(InboundRequest request) {
        log.debug("입고 요청 수신: requestId={}", request.requestId());
        return findByRequestId(request.requestId())
                .orElseGet(() -> applyOrRecover(request.requestId(), () -> mutationExecutor.applyInbound(request)));
    }

    public StockChangeResponse outbound(OutboundRequest request) {
        log.debug("출고 요청 수신: requestId={}", request.requestId());
        return findByRequestId(request.requestId())
                .orElseGet(() -> applyOrRecover(request.requestId(), () -> mutationExecutor.applyOutbound(request)));
    }

    @Transactional(readOnly = true)
    public Page<StockHistoryResponse> getHistory(Long productId, Pageable pageable) {
        log.debug("재고 이력 조회: productId={}, page={}, size={}", productId, pageable.getPageNumber(), pageable.getPageSize());
        productService.getOrThrow(productId);
        return stockHistoryRepository.findByProductId(productId, pageable).map(StockHistoryResponse::from);
    }

    /**
     * Runs a mutation, and if it fails on the request_id unique constraint (two concurrent
     * requests with the same idempotency key both passed the earlier findByRequestId check),
     * returns the winner's result instead of surfacing an error — duplicate submission of the
     * same request_id is defined as success, not a conflict.
     */
    private StockChangeResponse applyOrRecover(String requestId, Supplier<StockChangeResponse> mutation) {
        try {
            return mutation.get();
        } catch (DataIntegrityViolationException e) {
            log.error("requestId 유니크 제약 충돌 감지, 동시 요청의 처리 결과를 재조회합니다: requestId={}", requestId, e);
            return findByRequestId(requestId)
                    .orElseThrow(() -> {
                        log.error("requestId 충돌 복구 실패 - 재조회 결과 없음: requestId={}", requestId);
                        return e;
                    });
        }
    }

    private Optional<StockChangeResponse> findByRequestId(String requestId) {
        Optional<StockChangeResponse> existing = stockHistoryRepository.findByRequestId(requestId).map(this::toResponse);
        existing.ifPresent(response -> log.info("중복 요청 감지, 기존 처리 결과를 반환합니다: requestId={}", requestId));
        return existing;
    }

    private StockChangeResponse toResponse(StockHistory history) {
        Product product = productRepository.findById(history.getProductId())
                .orElseThrow(() -> {
                    log.error("이력에 연결된 상품을 찾을 수 없음: productId={}, historyId={}", history.getProductId(), history.getId());
                    return new ProductNotFoundException(history.getProductId());
                });
        return new StockChangeResponse(
                product.getId(), product.getProductCode(), history.getType(), history.getQuantity(),
                history.getBeforeQuantity(), history.getAfterQuantity(), history.getRequestId(), history.getCreatedAt());
    }
}
