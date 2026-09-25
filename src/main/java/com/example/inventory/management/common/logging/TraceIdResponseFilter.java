package com.example.inventory.management.common.logging;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns the request's traceId in the {@code X-Trace-Id} response header, so a client can report it
 * and the matching log lines (which carry the same traceId) can be found.
 *
 * <p>The span is started by Spring's observation filter, which runs before this filter.
 */
@Component
public class TraceIdResponseFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            response.addHeader(TRACE_ID_HEADER, currentSpan.getSpanContext().getTraceId());
        }

        filterChain.doFilter(request, response);
    }
}
