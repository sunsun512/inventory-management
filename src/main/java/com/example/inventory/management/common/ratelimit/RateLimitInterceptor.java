package com.example.inventory.management.common.ratelimit;

import com.example.inventory.management.common.exception.TooManyRequestsException;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * Limits POST requests per client IP; other methods pass without using a token.
 *
 * <p>This is an interceptor rather than a filter so that the rejection is thrown as an exception and
 * GlobalExceptionHandler writes the usual {@code ErrorResponse} body. The limit is checked before the
 * request body is read, so a malformed request still uses a token.
 *
 * <p>The client is identified by {@link HttpServletRequest#getRemoteAddr()} only. {@code X-Forwarded-For}
 * is not trusted, because a client could set it to a new value on every request to bypass the limit.
 * Behind a load balancer, configure {@code server.forward-headers-strategy} for the trusted proxy,
 * otherwise every request appears to come from the load balancer's IP.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private final PostRateLimiter rateLimiter;

    public RateLimitInterceptor(PostRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        ConsumptionProbe probe = rateLimiter.tryConsume(request.getRemoteAddr());
        if (!probe.isConsumed()) {
            throw new TooManyRequestsException(retryAfterSeconds(probe));
        }
        response.setHeader(REMAINING_HEADER, String.valueOf(probe.getRemainingTokens()));
        return true;
    }

    /** Retry-After is whole seconds, so round up: retrying earlier would be rejected again. */
    private static long retryAfterSeconds(ConsumptionProbe probe) {
        long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + TimeUnit.SECONDS.toNanos(1) - 1);
        return Math.max(1, seconds);
    }
}
