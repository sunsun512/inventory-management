package com.example.inventory.management.inventory.common.config;

import com.example.inventory.management.inventory.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OpenApiDocumentationTest extends AbstractIntegrationTest {

    private static final String API_DOCS = "/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 모든_API_경로가_문서화된다() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Inventory Management API"))
                .andExpect(jsonPath("$.paths['/api/v1/stocks/inbound'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/stocks/outbound'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products/{productId}/stock'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/products/{productId}/stock-histories'].get").exists());
    }

    @Test
    void 입고_요청_스키마에_검증_규칙이_반영되고_내부_검증_메서드는_노출되지_않는다() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.InboundRequest.required",
                        hasItems("productCode", "quantity", "requestId")))
                .andExpect(jsonPath("$.components.schemas.InboundRequest.properties.productCode.pattern").value("^[A-Z0-9]+$"))
                .andExpect(jsonPath("$.components.schemas.InboundRequest.properties.productCode.maxLength").value(64))
                .andExpect(jsonPath("$.components.schemas.InboundRequest.properties.quantity.maximum").value(10000))
                .andExpect(jsonPath("$.components.schemas.InboundRequest.properties", not(hasKey("productNameValid"))));
    }

    @Test
    void 출고_API는_비즈니스_에러_응답을_ErrorResponse_스키마로_문서화한다() throws Exception {
        String outbound = "$.paths['/api/v1/stocks/outbound'].post.responses";
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(outbound + "['200'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/StockChangeResponse"))
                .andExpect(jsonPath(outbound + "['409'].content['application/json'].schema.$ref")
                        .value("#/components/schemas/ErrorResponse"))
                .andExpect(jsonPath(outbound + "['503'].headers['Retry-After']").exists());
    }

    @Test
    void 재고_이력_조회는_페이지_파라미터를_개별_쿼리_파라미터로_문서화한다() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/products/{productId}/stock-histories'].get.parameters[*].name",
                        hasItems("productId", "page", "size", "sort")));
    }

    @Test
    void 스웨거_UI가_제공된다() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
