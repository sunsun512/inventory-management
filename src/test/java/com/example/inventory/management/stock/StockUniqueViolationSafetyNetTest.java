package com.example.inventory.management.stock;

import com.example.inventory.management.common.exception.ErrorCode;
import com.example.inventory.management.common.exception.InventoryException;
import com.example.inventory.management.product.ProductRepository;
import com.example.inventory.management.stock.dto.InboundRequest;
import com.example.inventory.management.stock.dto.OutboundRequest;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Optional;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/**
 * The advisory lock + in-transaction existence check and ON CONFLICT DO NOTHING normally keep the
 * unique constraints from ever firing. These tests bypass those checks with spies so a real 23505
 * reaches the service, pinning that uk_stock_history_request_id maps to 409 DUPLICATE_REQUEST and
 * uk_product_product_code maps to 409 PRODUCT_CODE_ALREADY_EXISTS rather than a 500.
 */
class StockUniqueViolationSafetyNetTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(StockUniqueViolationSafetyNetTest.class);

    @Autowired
    private StockService stockService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private StockHistoryRepository stockHistoryRepository;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @Test
    void requestId_유니크_제약_위반은_409_중복_요청으로_변환된다() {
        String code = uniqueCode("SAFEREQ");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 100L);
        String requestId = newRequestId();
        stockService.outbound(new OutboundRequest(productId, code, 1L, requestId));

        // Pretend the existence checks missed the committed row, so the INSERT hits the constraint.
        doReturn(false).when(stockHistoryRepository).existsByRequestId(requestId);

        assertThatThrownBy(() -> stockService.outbound(new OutboundRequest(productId, code, 1L, requestId)))
                .isInstanceOf(InventoryException.class)
                .extracting(e -> ((InventoryException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_REQUEST);
        log.error("예상된 DUPLICATE_REQUEST 변환 확인(uk_stock_history_request_id): requestId={}", requestId);

        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Long.class, productId))
                .isEqualTo(99L);
    }

    @Test
    void 상품코드_유니크_제약_위반은_409_상품코드_중복으로_변환된다() {
        String code = uniqueCode("SAFECODE");
        // Simulate a registration path that inserts without ON CONFLICT: a plain INSERT of a code
        // that already exists raises a real 23505 on uk_product_product_code.
        doAnswer(invocation -> {
            jdbcTemplate.update("INSERT INTO product (product_code, name, quantity) VALUES (?, '선점', 0)", code);
            jdbcTemplate.update("INSERT INTO product (product_code, name, quantity) VALUES (?, '중복', 0)", code);
            return Optional.empty();
        }).when(productRepository).insertProductIfAbsent(eq(code), anyString(), anyLong(), any());

        assertThatThrownBy(() -> stockService.inbound(new InboundRequest(null, code, "상품", 1L, newRequestId())))
                .isInstanceOf(InventoryException.class)
                .extracting(e -> ((InventoryException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRODUCT_CODE_ALREADY_EXISTS);
        log.error("예상된 PRODUCT_CODE_ALREADY_EXISTS 변환 확인(uk_product_product_code): productCode={}", code);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM product WHERE product_code = ?", Long.class, code))
                .isZero(); // rolled back with the failed request
    }
}
