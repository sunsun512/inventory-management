package com.example.inventory.management.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-client limit shared by all POST APIs.
 *
 * @param capacity        how many requests a client can send in one burst
 * @param refillPerSecond how many requests per second a client can keep sending on average
 */
@ConfigurationProperties("rate-limit.post")
public record RateLimitProperties(long capacity, long refillPerSecond) {
}
