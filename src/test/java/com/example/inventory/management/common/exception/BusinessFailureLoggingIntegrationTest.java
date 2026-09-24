package com.example.inventory.management.common.exception;

import com.example.inventory.management.product.ProductRepository;
import com.example.inventory.management.stock.StockHistoryRepository;
import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.example.inventory.management.support.ProductFixtures.insertProduct;
import static com.example.inventory.management.support.ProductFixtures.newRequestId;
import static com.example.inventory.management.support.ProductFixtures.uniqueCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that every failed request is logged exactly once, by GlobalExceptionHandler: business
 * failures (4xx) at WARN and unexpected integrity failures (5xx) at ERROR, never also at the throw
 * site. Only the application's own loggers are counted (framework loggers such as Hibernate's
 * SqlExceptionHelper are out of scope), and only output written while the request was handled.
 */
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BusinessFailureLoggingIntegrationTest extends AbstractIntegrationTest {

    private static final String INBOUND = "/api/v1/stocks/inbound";
    private static final String OUTBOUND = "/api/v1/stocks/outbound";

    /** A log event line (not a stack-trace line) at WARN/ERROR from a com.example.inventory logger. */
    private static final Pattern APP_WARN_OR_ERROR = Pattern.compile(
            "^\\S+\\s+(WARN|ERROR)\\s+\\d+\\s+---.*\\s(c\\.e\\.i\\.m\\.\\S*|com\\.example\\.inventory\\.\\S*)\\s+:.*$");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private ProductRepository productRepository;

    @MockitoSpyBean
    private StockHistoryRepository stockHistoryRepository;

    @Test
    void 재고_부족_출고는_예외_처리기에서_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGLOW");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 3L);
        int from = output.getOut().length();

        postJson(OUTBOUND, Map.of("productId", productId, "productCode", code, "quantity", 5, "requestId", newRequestId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value(containsString("requestedQuantity=5")));

        assertLoggedOnceAtWarn(output, from, "INSUFFICIENT_STOCK", "productId=" + productId, "requestedQuantity=5");
    }

    @Test
    void 이미_처리된_requestId는_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGDUP");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productId", productId, "productCode", code, "quantity", 1, "requestId", requestId);
        postJson(OUTBOUND, body).andExpect(status().isOk());
        int from = output.getOut().length();

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        assertLoggedOnceAtWarn(output, from, "DUPLICATE_REQUEST", requestId);
    }

    @Test
    void 트랜잭션_안에서_발견된_중복_requestId도_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGDUPTX");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productId", productId, "productCode", code, "quantity", 1, "requestId", requestId);
        postJson(OUTBOUND, body).andExpect(status().isOk());
        // The pre-transaction check misses the row; the in-transaction check (under the lock) finds it.
        doReturn(false).doReturn(true).when(stockHistoryRepository).existsByRequestId(requestId);
        int from = output.getOut().length();

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        assertLoggedOnceAtWarn(output, from, "DUPLICATE_REQUEST", requestId);
    }

    @Test
    void requestId_유니크_제약_충돌도_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGDUPUK");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        String requestId = newRequestId();
        Map<String, Object> body = Map.of("productId", productId, "productCode", code, "quantity", 1, "requestId", requestId);
        postJson(OUTBOUND, body).andExpect(status().isOk());
        // Both existence checks miss the row, so the INSERT hits uk_stock_history_request_id.
        doReturn(false).when(stockHistoryRepository).existsByRequestId(requestId);
        int from = output.getOut().length();

        postJson(OUTBOUND, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        assertLoggedOnceAtWarn(output, from, "DUPLICATE_REQUEST", requestId);
    }

    @Test
    void 존재하지_않는_상품_출고는_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        int from = output.getOut().length();

        postJson(OUTBOUND, Map.of("productId", 999_999, "productCode", "SKUANY", "quantity", 1, "requestId", newRequestId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        assertLoggedOnceAtWarn(output, from, "PRODUCT_NOT_FOUND", "productId=999999");
    }

    @Test
    void 존재하지_않는_상품_재고_조회는_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        int from = output.getOut().length();

        mockMvc.perform(get("/api/v1/products/{id}/stock", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));

        assertLoggedOnceAtWarn(output, from, "PRODUCT_NOT_FOUND", "productId=999999");
    }

    @Test
    void productCode_불일치는_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGMIS");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        int from = output.getOut().length();

        postJson(OUTBOUND, Map.of("productId", productId, "productCode", "SKUOTHER", "quantity", 1, "requestId", newRequestId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_MISMATCH"));

        assertLoggedOnceAtWarn(output, from, "PRODUCT_CODE_MISMATCH", "productId=" + productId, "SKUOTHER");
    }

    @Test
    void 이미_등록된_상품코드로_신규_등록하면_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGEXIST");
        insertProduct(jdbcTemplate, code, "상품", 10L);
        int from = output.getOut().length();

        postJson(INBOUND, Map.of("productCode", code, "productName", "상품", "quantity", 1, "requestId", newRequestId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRODUCT_CODE_ALREADY_EXISTS"));

        assertLoggedOnceAtWarn(output, from, "PRODUCT_CODE_ALREADY_EXISTS", code);
    }

    @Test
    void 재고_수량_범위_초과는_WARN으로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGOVER");
        Long productId = insertProduct(jdbcTemplate, code, "상품", Long.MAX_VALUE - 1);
        int from = output.getOut().length();

        postJson(INBOUND, Map.of("productId", productId, "productCode", code, "quantity", 10, "requestId", newRequestId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOCK_QUANTITY_OVERFLOW"));

        assertLoggedOnceAtWarn(output, from, "STOCK_QUANTITY_OVERFLOW", "productId=" + productId);
    }

    @Test
    void 분류되지_않은_무결성_위반은_ERROR로_한_번만_기록된다(CapturedOutput output) throws Exception {
        String code = uniqueCode("LOGDIV");
        Long productId = insertProduct(jdbcTemplate, code, "상품", 10L);
        doThrow(new DataIntegrityViolationException("simulated integrity failure",
                new SQLException("simulated not-null violation", "23502")))
                .when(productRepository).decreaseQuantityIfSufficient(eq(productId), anyLong(), any());
        int from = output.getOut().length();

        postJson(OUTBOUND, Map.of("productId", productId, "productCode", code, "quantity", 1, "requestId", newRequestId()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

        List<String> lines = appWarnOrErrorLines(output, from);
        assertThat(lines).as("app WARN/ERROR log lines").hasSize(1);
        assertThat(lines.get(0)).contains(" ERROR ", "GlobalExceptionHandler", "23502");
        assertThat(output.getOut().substring(from))
                .as("the single ERROR carries the stack trace")
                .contains("DataIntegrityViolationException: simulated integrity failure");
    }

    private void assertLoggedOnceAtWarn(CapturedOutput output, int from, String errorCode, String... context) {
        List<String> lines = appWarnOrErrorLines(output, from);
        assertThat(lines).as("app WARN/ERROR log lines").hasSize(1);
        assertThat(lines.get(0)).contains(" WARN ", "GlobalExceptionHandler", errorCode).contains(context);
    }

    private static List<String> appWarnOrErrorLines(CapturedOutput output, int from) {
        return output.getOut().substring(from).lines()
                .filter(line -> APP_WARN_OR_ERROR.matcher(line).matches())
                .toList();
    }

    private ResultActions postJson(String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
