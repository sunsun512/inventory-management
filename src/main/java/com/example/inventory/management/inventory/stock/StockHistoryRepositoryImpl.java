package com.example.inventory.management.inventory.stock;

import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;

import static com.example.inventory.management.inventory.stock.QStockHistory.stockHistory;

class StockHistoryRepositoryImpl implements StockHistoryRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    StockHistoryRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * Projects straight into the response DTO (no entities, no lazy associations to trigger
     * follow-up queries) and always orders by id DESC — the real apply order per product, served
     * by idx_stock_history_product_id_id. The pageable's sort is deliberately ignored. The count
     * query only runs when the page alone cannot determine the total.
     */
    @Override
    public Page<StockHistoryResponse> findHistories(Long productId, Pageable pageable) {
        List<StockHistoryResponse> content = queryFactory
                .select(Projections.constructor(StockHistoryResponse.class,
                        stockHistory.id,
                        stockHistory.type,
                        stockHistory.quantity,
                        stockHistory.beforeQuantity,
                        stockHistory.afterQuantity,
                        stockHistory.requestId,
                        stockHistory.createdAt))
                .from(stockHistory)
                .where(stockHistory.productId.eq(productId))
                .orderBy(stockHistory.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(stockHistory.count())
                .from(stockHistory)
                .where(stockHistory.productId.eq(productId));

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }
}
