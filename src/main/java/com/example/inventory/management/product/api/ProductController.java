package com.example.inventory.management.product.api;

import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.product.query.ProductQueryService;
import com.example.inventory.management.product.query.dto.ProductDetailResponse;
import com.example.inventory.management.product.query.dto.ProductSummaryResponse;
import com.example.inventory.management.product.query.dto.StockQuantityResponse;
import com.example.inventory.management.stock.query.StockHistoryQueryService;
import com.example.inventory.management.stock.query.dto.StockHistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController implements ProductApi {

    private final ProductQueryService productQueryService;
    private final StockHistoryQueryService stockHistoryQueryService;

    public ProductController(ProductQueryService productQueryService, StockHistoryQueryService stockHistoryQueryService) {
        this.productQueryService = productQueryService;
        this.stockHistoryQueryService = stockHistoryQueryService;
    }

    @Override
    @GetMapping
    public PageResponse<ProductSummaryResponse> getProducts(
            @RequestParam(required = false) String productCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return productQueryService.getProducts(productCode, page, size);
    }

    @Override
    @GetMapping("/{productId}")
    public ProductDetailResponse getProduct(@PathVariable Long productId) {
        return productQueryService.getProduct(productId);
    }

    @Override
    @GetMapping("/{productId}/stock")
    public StockQuantityResponse getStock(@PathVariable Long productId) {
        return productQueryService.getStock(productId);
    }

    @Override
    @GetMapping("/{productId}/stock-histories")
    public PageResponse<StockHistoryResponse> getStockHistories(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return stockHistoryQueryService.getHistory(productId, page, size);
    }
}
