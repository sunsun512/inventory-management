package com.example.inventory.management.product;

import com.example.inventory.management.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductRepositoryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductRepositoryTest.class);

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void 존재하지_않는_상품코드면_상품이_등록된다() {
        log.debug("신규 상품 등록 테스트 시작: productCode=SKU1");
        Optional<ProductRepository.InsertedProduct> result =
                productRepository.insertProductIfAbsent("SKU1", "상품 A", 10L, Instant.now());

        assertThat(result).isPresent();
        assertThat(result.get().getQuantity()).isEqualTo(10L);
        assertThat(result.get().getId()).isNotNull();
        log.info("신규 상품 등록 확인: productId={}, quantity={}", result.get().getId(), result.get().getQuantity());
    }

    @Test
    void 이미_존재하는_상품코드면_빈_값을_반환하고_기존_상품은_변하지_않는다() {
        log.debug("기존 상품코드 등록 시도 테스트 시작: productCode=SKU2");
        Long existingId = insertProduct(jdbcTemplate, "SKU2", "상품 B", 10L);

        Optional<ProductRepository.InsertedProduct> result =
                productRepository.insertProductIfAbsent("SKU2", "다른 이름", 5L, Instant.now());

        assertThat(result).isEmpty();
        entityManager.clear();
        Product product = productRepository.findById(existingId).orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(10L);
        assertThat(product.getName()).isEqualTo("상품 B");
        log.error("이미 존재하는 상품코드 등록 거부 확인(예상된 결과): productId={}, quantity={}", existingId, product.getQuantity());
    }

    @Test
    void 수량_증가_쿼리는_증가된_수량을_반환한다() {
        log.debug("수량 증가 쿼리 테스트 시작: productCode=SKU3");
        Long productId = insertProduct(jdbcTemplate, "SKU3", "상품 C", 10L);

        Optional<Long> after = productRepository.increaseQuantity(productId, 7L, Instant.now());

        assertThat(after).contains(17L);
        log.info("수량 증가 쿼리 결과 확인: productId={}, afterQuantity={}", productId, after.orElse(null));
    }

    @Test
    void 재고가_충분하면_수량_감소에_성공한다() {
        log.debug("재고 충분 시 수량 감소 테스트 시작: productCode=SKU4");
        Long productId = insertProduct(jdbcTemplate, "SKU4", "상품 D", 10L);

        Optional<Long> after = productRepository.decreaseQuantityIfSufficient(productId, 4L, Instant.now());

        assertThat(after).contains(6L);
        log.info("수량 감소 쿼리 결과 확인: productId={}, afterQuantity={}", productId, after.orElse(null));
    }

    @Test
    void 재고가_부족하면_빈_값을_반환하고_수량은_변하지_않는다() {
        log.debug("재고 부족 시 수량 감소 테스트 시작: productCode=SKU5");
        Long productId = insertProduct(jdbcTemplate, "SKU5", "상품 E", 10L);

        Optional<Long> after = productRepository.decreaseQuantityIfSufficient(productId, 11L, Instant.now());

        if (after.isEmpty()) {
            log.error("재고 부족으로 수량 감소 거부됨(예상된 결과): productId={}, 요청수량=11", productId);
        }
        assertThat(after).isEmpty();

        Product product = productRepository.findById(productId).orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(10L);
    }
}
