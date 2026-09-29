package com.example.inventory.management.stock.query;

import com.example.inventory.management.common.exception.ProductNotFoundException;
import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.product.domain.ProductRepository;
import com.example.inventory.management.stock.query.dto.StockHistoryResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
public class StockHistoryQueryService {

    private final StockHistoryQueryRepository stockHistoryQueryRepository;
    private final ProductRepository productRepository;

    public StockHistoryQueryService(StockHistoryQueryRepository stockHistoryQueryRepository,
                                    ProductRepository productRepository) {
        this.stockHistoryQueryRepository = stockHistoryQueryRepository;
        this.productRepository = productRepository;
    }

    /**
     * A page of the product's histories in the fixed order {@code createdAt DESC, id DESC}.
     * {@code size} above {@link PageResponse#MAX_SIZE} is clamped to it.
     */
    public PageResponse<StockHistoryResponse> getHistory(Long productId, int page, int size) {
        int pageSize = Math.min(size, PageResponse.MAX_SIZE);
        log.debug("재고 이력 조회: productId={}, page={}, size={}", productId, page, pageSize);
        if (!productRepository.existsById(productId)) {
            throw new ProductNotFoundException(productId);
        }
        return stockHistoryQueryRepository.findByProductId(productId, page, pageSize);
    }
}
