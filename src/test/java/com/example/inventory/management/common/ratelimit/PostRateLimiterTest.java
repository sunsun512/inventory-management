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
    void 오래_쉬어도_다_채워지기_전에는_가득_찬_새_버킷을_받지_않는다() {
        // 빈 버킷이 가득 차려면 3600초(1시간)가 걸린다.
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(3600, 1), timeMeter, timeMeter);
        for (int i = 0; i < 3600; i++) {
            rateLimiter.tryConsume(CLIENT);
        }

        timeMeter.advance(Duration.ofMinutes(11));

        // 11분 동안 660개만 다시 찼으므로, 1개를 쓰면 659개가 남아야 한다.
        assertThat(rateLimiter.tryConsume(CLIENT).getRemainingTokens()).isEqualTo(659);
    }

    @Test
    void 나누어떨어지지_않아도_다_채워지기_전에는_버킷을_버리지_않는다() {
        // 초당 3개씩 채워지므로 빈 버킷(용량 1)이 가득 차려면 333,333,333.33...ns → 333,333,334ns가 걸린다.
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(1, 3), timeMeter, timeMeter);
        rateLimiter.tryConsume(CLIENT);

        timeMeter.advance(Duration.ofNanos(333_333_333));

        // 아직 1개가 다 차지 않았으므로 거절되어야 한다. 만료를 내림으로 계산하면 가득 찬 새 버킷을 받아 통과해 버린다.
        assertThat(rateLimiter.tryConsume(CLIENT).isConsumed()).isFalse();
    }

    @Test
    void 용량이_매우_커도_생성할_수_있다() {
        // 용량 100억, 초당 100만 → 가득 차는 데 10,000초. 용량 × 10^9ns 가 long 범위를 넘는 값이다.
        PostRateLimiter rateLimiter = new PostRateLimiter(new RateLimitProperties(10_000_000_000L, 1_000_000), timeMeter);

        assertThat(rateLimiter.tryConsume(CLIENT).getRemainingTokens()).isEqualTo(9_999_999_999L);
        assertThat(new PostRateLimiter(new RateLimitProperties(Long.MAX_VALUE, 1), timeMeter)
                .tryConsume(CLIENT).isConsumed()).isTrue();
    }

    @Test
    void 재충전_속도가_초당_10억을_넘으면_생성할_수_없다() {
        // Bucket4j가 지원하는 최대 재충전 속도는 1 token/ns = 초당 10억 개다.
        assertThat(new PostRateLimiter(new RateLimitProperties(1, 1_000_000_000L), timeMeter)
                .tryConsume(CLIENT).isConsumed()).isTrue();
        assertThatThrownBy(() -> new PostRateLimiter(new RateLimitProperties(1, 1_000_000_001L), timeMeter))
                .isInstanceOf(IllegalArgumentException.class);
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
