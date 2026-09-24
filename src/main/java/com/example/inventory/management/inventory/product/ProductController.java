package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.exception.InvalidSortPropertyException;
import com.example.inventory.management.inventory.product.dto.StockQuantityResponse;
import com.example.inventory.management.inventory.stock.StockService;
import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    /**
     * Sort properties clients may request on stock histories. Anything else is rejected with
     * 400 before it reaches the query (an unknown property would otherwise fail inside Spring
     * Data, and other entity columns are not meant to be exposed as sort keys).
     */
    static final List<String> STOCK_HISTORY_SORT_PROPERTIES = List.of("id", "createdAt");

    private final ProductService productService;
    private final StockService stockService;

    public ProductController(ProductService productService, StockService stockService) {
        this.productService = productService;
        this.stockService = stockService;
    }

    @GetMapping("/{productId}/stock")
    public StockQuantityResponse getStock(@PathVariable Long productId) {
        Product product = productService.getOrThrow(productId);
        return new StockQuantityResponse(product.getId(), product.getProductCode(), product.getName(), product.getQuantity());
    }

    @GetMapping("/{productId}/stock-histories")
    public Page<StockHistoryResponse> getStockHistories(
            @PathVariable Long productId,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        validateSort(pageable.getSort(), STOCK_HISTORY_SORT_PROPERTIES);
        return stockService.getHistory(productId, pageable);
    }

    private static void validateSort(Sort sort, List<String> allowed) {
        for (Sort.Order order : sort) {
            if (!allowed.contains(order.getProperty())) {
                throw new InvalidSortPropertyException(order.getProperty(), allowed);
            }
        }
    }
}
