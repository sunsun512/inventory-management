package com.example.inventory.management.stock.query;

import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.stock.domain.StockHistory;
import com.example.inventory.management.stock.domain.StockHistoryRepository;
import com.example.inventory.management.stock.query.dto.StockHistoryResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.example.inventory.management.stock.domain.QStockHistory.stockHistory;

/**
 * QueryDSL reads of {@link StockHistory}. Writes and simple lookups stay on the Spring Data
 * {@link StockHistoryRepository}.
 */
@Repository
public class StockHistoryQueryRepository {

    private final JPAQueryFactory queryFactory;

    public StockHistoryQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * A product's histories, newest first, projected straight into the response row so no entity is
     * loaded into the persistence context. The order is fixed: {@code createdAt DESC}, with
     * {@code id DESC} as tie-breaker so rows sharing a timestamp keep a stable order across pages.
     * No count query runs; {@code size + 1} rows are read to tell whether a next page exists.
     */
    public PageResponse<StockHistoryResponse> findByProductId(Long productId, int page, int size) {
        List<StockHistoryResponse> rows = queryFactory
                .select(Projections.constructor(StockHistoryResponse.class,
                        stockHistory.id, stockHistory.type, stockHistory.quantity, stockHistory.beforeQuantity,
                        stockHistory.afterQuantity, stockHistory.requestId, stockHistory.createdAt))
                .from(stockHistory)
                .where(stockHistory.productId.eq(productId))
                .orderBy(stockHistory.createdAt.desc(), stockHistory.id.desc())
                .offset(PageResponse.offset(page, size))
                .limit(PageResponse.fetchLimit(size))
                .fetch();
        return PageResponse.of(rows, page, size);
    }
}
