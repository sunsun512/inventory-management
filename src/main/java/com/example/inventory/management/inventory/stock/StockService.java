package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.exception.DuplicateRequestException;
import com.example.inventory.management.inventory.common.exception.ProductCodeAlreadyExistsException;
import com.example.inventory.management.inventory.common.exception.SqlStates;
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

import java.util.function.Supplier;

/**
 * A requestId is processed only by the request whose transaction first commits successfully;
 * every later or concurrent request with the same requestId is rejected with 409
 * DUPLICATE_REQUEST (the original result is not returned). Only successful requests are stored,
 * so a request that failed (400/404/409/503) leaves its requestId free for a retry.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    static final String REQUEST_ID_UNIQUE_CONSTRAINT = "uk_stock_history_request_id";
    static final String PRODUCT_CODE_UNIQUE_CONSTRAINT = "uk_product_product_code";

    private final StockHistoryRepository stockHistoryRepository;
    private final ProductService productService;
    private final StockMutationExecutor mutationExecutor;

    public StockService(StockHistoryRepository stockHistoryRepository,
                         ProductService productService,
                         StockMutationExecutor mutationExecutor) {
        this.stockHistoryRepository = stockHistoryRepository;
        this.productService = productService;
        this.mutationExecutor = mutationExecutor;
    }

    public StockChangeResponse inbound(InboundRequest request) {
        log.debug("입고 요청 수신: requestId={}", request.requestId());
        rejectIfAlreadyProcessed(request.requestId());
        return translateUniqueViolation(request.requestId(), request.productCode(),
                () -> mutationExecutor.applyInbound(request));
    }

    public StockChangeResponse outbound(OutboundRequest request) {
        log.debug("출고 요청 수신: requestId={}", request.requestId());
        rejectIfAlreadyProcessed(request.requestId());
        return translateUniqueViolation(request.requestId(), request.productCode(),
                () -> mutationExecutor.applyOutbound(request));
    }

    @Transactional(readOnly = true)
    public Page<StockHistoryResponse> getHistory(Long productId, Pageable pageable) {
        log.debug("재고 이력 조회: productId={}, page={}, size={}", productId, pageable.getPageNumber(), pageable.getPageSize());
        productService.getOrThrow(productId);
        return stockHistoryRepository.findHistories(productId, pageable);
    }

    /**
     * Cheap pre-transaction check for the common sequential retry. It is only an optimization: the
     * authoritative check runs again inside the mutation transaction under the requestId lock.
     */
    private void rejectIfAlreadyProcessed(String requestId) {
        if (stockHistoryRepository.existsByRequestId(requestId)) {
            log.error("중복 요청 거절 - 이미 처리된 requestId: requestId={}", requestId);
            throw new DuplicateRequestException(requestId);
        }
    }

    /**
     * Safety net: the requestId lock + in-transaction check and ON CONFLICT DO NOTHING normally
     * prevent these unique violations, but if one still fires it is the same conflict and maps to
     * the same 409. Any other integrity failure is rethrown untouched.
     */
    private StockChangeResponse translateUniqueViolation(String requestId, String productCode,
                                                         Supplier<StockChangeResponse> mutation) {
        try {
            return mutation.get();
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.isUniqueViolationOf(e, REQUEST_ID_UNIQUE_CONSTRAINT)) {
                log.error("requestId 유니크 제약 충돌 - 중복 요청으로 거절합니다: requestId={}", requestId);
                throw new DuplicateRequestException(requestId);
            }
            if (SqlStates.isUniqueViolationOf(e, PRODUCT_CODE_UNIQUE_CONSTRAINT)) {
                log.error("상품코드 유니크 제약 충돌 - 상품코드 중복으로 거절합니다: productCode={}, requestId={}",
                        productCode, requestId);
                throw new ProductCodeAlreadyExistsException(productCode);
            }
            log.error("재고 변경 중 데이터 무결성 위반 발생: requestId={}, sqlState={}",
                    requestId, SqlStates.of(e).orElse("unknown"));
            throw e;
        }
    }
}
