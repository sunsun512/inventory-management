package com.example.inventory.management.common.config;

import com.example.inventory.management.common.ratelimit.PostRateLimiter;
import com.example.inventory.management.common.ratelimit.RateLimitInterceptor;
import com.example.inventory.management.common.ratelimit.RateLimitProperties;
import io.github.bucket4j.TimeMeter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies the per-client rate limit to every API; RateLimitInterceptor itself only limits POST. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {

    private final PostRateLimiter rateLimiter;

    public RateLimitConfig(RateLimitProperties properties) {
        this.rateLimiter = new PostRateLimiter(properties, TimeMeter.SYSTEM_NANOTIME);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(rateLimiter))
                .addPathPatterns("/api/**");
    }
}
