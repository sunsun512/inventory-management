package com.example.inventory.management.product;

import com.example.inventory.management.common.exception.ProductNotFoundException;
import com.example.inventory.management.common.response.PageResponse;
import com.example.inventory.management.product.dto.ProductDetailResponse;
import com.example.inventory.management.product.dto.ProductSummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;
    private final ProductQueryRepository productQueryRepository;

    public ProductService(ProductRepository productRepository, ProductQueryRepository productQueryRepository) {
        this.productRepository = productRepository;
        this.productQueryRepository = productQueryRepository;
    }

    public Product getOrThrow(Long productId) {
        log.debug("상품 조회 시도: productId={}", productId);
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException(productId));
    }

    /**
     * A page of products, newest registration first ({@code id DESC}). A non-null
     * {@code productCode} narrows it to that exact code, which lets a client find a product's id
     * from its code. {@code size} above {@link PageResponse#MAX_SIZE} is clamped to it.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> getProducts(String productCode, int page, int size) {
        int pageSize = Math.min(size, PageResponse.MAX_SIZE);
        log.debug("상품 목록 조회: productCode={}, page={}, size={}", productCode, page, pageSize);
        return productQueryRepository.findSummaries(productCode, page, pageSize);
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProduct(Long productId) {
        return ProductDetailResponse.from(getOrThrow(productId));
    }
}
