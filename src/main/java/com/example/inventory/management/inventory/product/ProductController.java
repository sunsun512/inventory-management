package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.response.PageResponse;
import com.example.inventory.management.inventory.product.dto.ProductDetailResponse;
import com.example.inventory.management.inventory.product.dto.ProductSummaryResponse;
import com.example.inventory.management.inventory.product.dto.StockQuantityResponse;
import com.example.inventory.management.inventory.stock.StockService;
import com.example.inventory.management.inventory.stock.dto.StockHistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController implements ProductApi {

    private final ProductService productService;
    private final StockService stockService;

    public ProductController(ProductService productService, StockService stockService) {
        this.productService = productService;
        this.stockService = stockService;
    }

    @Override
    @GetMapping
    public PageResponse<ProductSummaryResponse> getProducts(
            @RequestParam(required = false) String productCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return productService.getProducts(productCode, page, size);
    }

    @Override
    @GetMapping("/{productId}")
    public ProductDetailResponse getProduct(@PathVariable Long productId) {
        return productService.getProduct(productId);
    }

    @Override
    @GetMapping("/{productId}/stock")
    public StockQuantityResponse getStock(@PathVariable Long productId) {
        Product product = productService.getOrThrow(productId);
        return new StockQuantityResponse(product.getId(), product.getProductCode(), product.getName(), product.getQuantity());
    }

    /**
     * The order is fixed server-side; a {@code sort} query parameter is not bound and is ignored.
     * The {@code page >= 0} / {@code size >= 1} constraints are declared on {@link ProductApi}
     * (Bean Validation forbids re-declaring parameter constraints on an implementing method).
     */
    @Override
    @GetMapping("/{productId}/stock-histories")
    public PageResponse<StockHistoryResponse> getStockHistories(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return stockService.getHistory(productId, page, size);
    }
}
