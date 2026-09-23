package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.common.exception.ProductNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product getOrThrow(Long productId) {
        log.debug("상품 조회 시도: productId={}", productId);
        return productRepository.findById(productId)
                .orElseThrow(() -> {
                    log.error("상품 조회 실패 - 존재하지 않는 productId={}", productId);
                    return new ProductNotFoundException(productId);
                });
    }
}
