package com.example.inventory.management.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Logs one {@code [REQ]} line when a request arrives and one {@code [RES]} line with the status and
 * elapsed time when it completes, including when the request fails with an exception.
 *
 * <p>Both lines are INFO on the {@code API_LOGGER} logger. Failures are still logged at WARN/ERROR only
 * by GlobalExceptionHandler. Health-check probes are excluded. Request bodies are not logged.
 */
@Slf4j(topic = "API_LOGGER")
@Component
public class ApiLoggingFilter extends OncePerRequestFilter {

    private static final List<String> EXCLUDED_PREFIXES = List.of("/actuator", "/livez", "/readyz");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        return EXCLUDED_PREFIXES.stream().anyMatch(requestUri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String queryString = request.getQueryString();
        String uri = queryString == null ? request.getRequestURI() : request.getRequestURI() + "?" + queryString;
        long startTime = System.nanoTime();

        log.info("[REQ] {} {}", request.getMethod(), uri);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            log.info("[RES] {} {} ({}ms)", response.getStatus(), uri, durationMs);
        }
    }
}
