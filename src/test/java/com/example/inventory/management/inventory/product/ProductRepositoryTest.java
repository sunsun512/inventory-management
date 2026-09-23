package com.example.inventory.management.inventory.product;

import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
class ProductRepositoryTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ProductRepositoryTest.class);

    @Autowired
    private ProductRepository productRepository;

    @Test
    void 신규_상품_업서트시_상품이_등록된다() {
        log.debug("신규 상품 업서트 테스트 시작: productCode=SKU-1");
        ProductRepository.UpsertResult result =
                productRepository.upsertProductStock("SKU-1", "상품 A", 10L);

        assertThat(result.getQuantity()).isEqualTo(10L);
        assertThat(result.getInserted()).isTrue();
        assertThat(result.getId()).isNotNull();
        log.info("신규 상품 등록 확인: productId={}, quantity={}", result.getId(), result.getQuantity());
    }

    @Test
    void 기존_상품_업서트시_수량이_증가한다() {
        log.debug("기존 상품 업서트 테스트 시작: productCode=SKU-2");
        ProductRepository.UpsertResult first =
                productRepository.upsertProductStock("SKU-2", "상품 B", 10L);
        ProductRepository.UpsertResult second =
                productRepository.upsertProductStock("SKU-2", "상품 B", 5L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getQuantity()).isEqualTo(15L);
        assertThat(second.getInserted()).isFalse();
        log.info("기존 상품 수량 증가 확인: productId={}, quantity={}", second.getId(), second.getQuantity());
    }

    @Test
    void 수량_증가_쿼리는_증가된_수량을_반환한다() {
        log.debug("수량 증가 쿼리 테스트 시작: productCode=SKU-3");
        ProductRepository.UpsertResult created =
                productRepository.upsertProductStock("SKU-3", "상품 C", 10L);

        Optional<Long> after = productRepository.increaseQuantity(created.getId(), 7L);

        assertThat(after).contains(17L);
        log.info("수량 증가 쿼리 결과 확인: productId={}, afterQuantity={}", created.getId(), after.orElse(null));
    }

    @Test
    void 재고가_충분하면_수량_감소에_성공한다() {
        log.debug("재고 충분 시 수량 감소 테스트 시작: productCode=SKU-4");
        ProductRepository.UpsertResult created =
                productRepository.upsertProductStock("SKU-4", "상품 D", 10L);

        Optional<Long> after = productRepository.decreaseQuantityIfSufficient(created.getId(), 4L);

        assertThat(after).contains(6L);
        log.info("수량 감소 쿼리 결과 확인: productId={}, afterQuantity={}", created.getId(), after.orElse(null));
    }

    @Test
    void 재고가_부족하면_빈_값을_반환하고_수량은_변하지_않는다() {
        log.debug("재고 부족 시 수량 감소 테스트 시작: productCode=SKU-5");
        ProductRepository.UpsertResult created =
                productRepository.upsertProductStock("SKU-5", "상품 E", 10L);

        Optional<Long> after = productRepository.decreaseQuantityIfSufficient(created.getId(), 11L);

        if (after.isEmpty()) {
            log.error("재고 부족으로 수량 감소 거부됨(예상된 결과): productId={}, 요청수량=11", created.getId());
        }
        assertThat(after).isEmpty();

        Product product = productRepository.findById(created.getId()).orElseThrow();
        assertThat(product.getQuantity()).isEqualTo(10L);
    }
}
