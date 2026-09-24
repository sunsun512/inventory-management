package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.response.PageResponse;
import com.example.inventory.management.inventory.product.dto.ProductSummaryResponse;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.example.inventory.management.inventory.product.QProduct.product;

/**
 * QueryDSL reads of {@link Product}. Writes and simple lookups stay on the Spring Data
 * {@link ProductRepository}.
 */
@Repository
public class ProductQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ProductQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * Products, newest registration first ({@code id DESC}), projected straight into the list row so
     * only the listed columns are read and no entity is loaded into the persistence context.
     * A non-null {@code productCode} is an exact-match filter (served by the unique index). No count
     * query runs; {@code size + 1} rows are read to tell whether a next page exists.
     */
    public PageResponse<ProductSummaryResponse> findSummaries(String productCode, int page, int size) {
        List<ProductSummaryResponse> rows = queryFactory
                .select(Projections.constructor(ProductSummaryResponse.class,
                        product.id, product.productCode, product.name, product.quantity))
                .from(product)
                .where(productCodeEq(productCode))
                .orderBy(product.id.desc())
                .offset(PageResponse.offset(page, size))
                .limit(PageResponse.fetchLimit(size))
                .fetch();
        return PageResponse.of(rows, page, size);
    }

    /** {@code null} (no filter) when no productCode is given; QueryDSL ignores a null predicate. */
    private static BooleanExpression productCodeEq(String productCode) {
        return productCode == null ? null : product.productCode.eq(productCode);
    }
}
