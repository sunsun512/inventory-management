package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.common.response.PageResponse;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.example.inventory.management.inventory.stock.QStockHistory.stockHistory;

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
     * A product's histories, newest first. The order is fixed: {@code createdAt DESC}, with
     * {@code id DESC} as tie-breaker so rows sharing a timestamp keep a stable order across pages.
     * No count query runs; {@code size + 1} rows are read to tell whether a next page exists.
     */
    public PageResponse<StockHistory> findByProductId(Long productId, int page, int size) {
        List<StockHistory> rows = queryFactory
                .selectFrom(stockHistory)
                .where(stockHistory.productId.eq(productId))
                .orderBy(stockHistory.createdAt.desc(), stockHistory.id.desc())
                .offset(PageResponse.offset(page, size))
                .limit(PageResponse.fetchLimit(size))
                .fetch();
        return PageResponse.of(rows, page, size);
    }
}
