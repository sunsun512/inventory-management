package com.example.inventory.management.common.ratelimit;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Uses its own small limit (capacity 2) instead of the high limit the other integration tests run with.
 * Buckets live for the whole Spring context, so each test uses its own client IP.
 *
 * <p>Requests send an empty body: the limit is checked before the body is read, so each one still uses a
 * token and is rejected with 400 without touching the database.
 */
@SpringBootTest(properties = {
        "rate-limit.post.capacity=2",
        "rate-limit.post.refill-per-second=1"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final String INBOUND = "/api/v1/stocks/inbound";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 한도를_넘은_POST는_429와_Retry_After를_반환한다() throws Exception {
        String client = "10.0.1.1";
        emptyPost(INBOUND, client).andExpect(status().isBadRequest());
        emptyPost(INBOUND, client).andExpect(status().isBadRequest());

        emptyPost(INBOUND, client)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void 통과한_POST_응답에는_남은_토큰_수가_담긴다() throws Exception {
        emptyPost(INBOUND, "10.0.1.2")
                .andExpect(header().string(RateLimitInterceptor.REMAINING_HEADER, "1"));
    }

    @Test
    void 한도는_모든_POST_API가_함께_쓴다() throws Exception {
        String client = "10.0.1.3";
        emptyPost(INBOUND, client).andExpect(status().isBadRequest());
        emptyPost("/api/v1/stocks/outbound", client).andExpect(status().isBadRequest());

        emptyPost(INBOUND, client).andExpect(status().isTooManyRequests());
    }

    @Test
    void 다른_IP는_따로_제한한다() throws Exception {
        String client = "10.0.1.4";
        emptyPost(INBOUND, client);
        emptyPost(INBOUND, client);
        emptyPost(INBOUND, client).andExpect(status().isTooManyRequests());

        emptyPost(INBOUND, "10.0.1.5").andExpect(status().isBadRequest());
    }

    @Test
    void GET_요청은_제한하지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/products").param("size", "1").with(remoteAddr("10.0.1.6")))
                    .andExpect(status().isOk());
        }
    }

    private ResultActions emptyPost(String uri, String client) throws Exception {
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .with(remoteAddr(client)));
    }

    private static RequestPostProcessor remoteAddr(String client) {
        return request -> {
            request.setRemoteAddr(client);
            return request;
        };
    }
}
