package com.example.inventory.management.inventory.product;

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

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

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
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return stockService.getHistory(productId, pageable);
    }
}
