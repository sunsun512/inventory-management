package com.example.inventory.management.common.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostRateLimiterTest {

    private static final String CLIENT = "10.0.0.1";

    private final FakeTimeMeter timeMeter = new FakeTimeMeter();

    @Test
    void 용량만큼은_허용하고_초과하면_거절한다() {
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(3, 1), timeMeter);

        for (int i = 0; i < 3; i++) {
            assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isTrue();
        }
        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isFalse();
    }

    @Test
    void 통과하면_남은_토큰_수를_알려준다() {
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(3, 1), timeMeter);

        assertThat(rateLimiter.tryConsume(CLIENT).getRemainingTokens()).isEqualTo(2);
        assertThat(rateLimiter.tryConsume(CLIENT).getRemainingTokens()).isEqualTo(1);
    }

    @Test
    void 거절되면_토큰이_다시_찰_때까지_기다릴_시간을_알려준다() {
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(1, 2), timeMeter);
        rateLimiter.tryConsume(CLIENT);

        ConsumptionProbe rejected = rateLimiter.tryConsume(CLIENT);

        // 초당 2개씩 채워지므로 토큰 1개는 0.5초 뒤에 생긴다.
        assertThat(rejected.isConsumed()).isFalse();
        assertThat(rejected.getNanosToWaitForRefill()).isEqualTo(Duration.ofMillis(500).toNanos());
    }

    @Test
    void 시간이_지나면_토큰이_다시_채워진다() {
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(2, 1), timeMeter);
        rateLimiter.tryConsume(CLIENT);
        rateLimiter.tryConsume(CLIENT);
        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isFalse();

        timeMeter.advance(Duration.ofSeconds(1));

        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isTrue();
        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isFalse();
    }

    @Test
    void 클라이언트마다_버킷을_따로_관리한다() {
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(1, 1), timeMeter);
        rateLimiter.tryConsume(CLIENT);
        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isFalse();

        assertThat(rateLimiter.tryConsume("10.0.0.2").isConsumed()).isTrue();
    }

    @Test
    void 용량이나_재충전_속도가_0이하면_생성할_수_없다() {
        assertThatThrownBy(() -> new PostRateLimiter(new RateLimitProperties(0, 1), timeMeter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostRateLimiter(new RateLimitProperties(1, 0), timeMeter))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
