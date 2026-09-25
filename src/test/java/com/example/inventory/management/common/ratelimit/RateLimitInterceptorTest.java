package com.example.inventory.management.common.ratelimit;

import com.example.inventory.management.common.exception.ErrorCode;
import com.example.inventory.management.common.exception.TooManyRequestsException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitInterceptorTest {

    private static final String CLIENT = "10.0.0.1";

    private final RateLimitInterceptor interceptor =
            new RateLimitInterceptor(new PostRateLimiter(new RateLimitProperties(1, 1), new FakeTimeMeter()));

    @Test
    void POST가_아닌_요청은_제한하지_않고_토큰도_쓰지_않는다() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(interceptor.preHandle(request("GET"), new MockHttpServletResponse(), new Object())).isTrue();
        }

        assertThat(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void 통과한_POST_응답에는_남은_토큰_수를_헤더로_알려준다() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request("POST"), response, new Object())).isTrue();

        assertThat(response.getHeader(RateLimitInterceptor.REMAINING_HEADER)).isEqualTo("0");
    }

    @Test
    void 한도를_넘은_POST는_재시도_대기_시간과_함께_거절한다() throws Exception {
        interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object());

        assertThatThrownBy(() -> interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(TooManyRequestsException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(ex.getRetryAfterSeconds()).isEqualTo(1);
                });
    }

    @Test
    void 재시도_대기_시간은_초_단위로_올림한다() throws Exception {
        // 초당 2개 재충전: 실제 대기 시간은 0.5초지만 Retry-After는 정수 초이므로 1초로 알려준다.
        RateLimitInterceptor fastRefill =
                new RateLimitInterceptor(new PostRateLimiter(new RateLimitProperties(1, 2), new FakeTimeMeter()));
        fastRefill.preHandle(request("POST"), new MockHttpServletResponse(), new Object());

        assertThatThrownBy(() -> fastRefill.preHandle(request("POST"), new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(TooManyRequestsException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/stocks/inbound");
        request.setRemoteAddr(CLIENT);
        return request;
    }
}
