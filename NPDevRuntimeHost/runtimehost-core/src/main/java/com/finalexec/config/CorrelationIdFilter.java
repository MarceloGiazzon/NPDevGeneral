package com.finalexec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * LNCH-8: stamps every request with a correlation id in MDC (so every log line for that request
 * -- across filters, controllers, and KernelRunner's own flow-outcome logging, which already
 * threads correlationId through separately at the flow-execution level -- carries the same id)
 * and echoes it back as a response header so a caller can quote it back when reporting an issue.
 * Reuses an inbound {@code X-Correlation-Id} if the caller already has one (e.g. a request that
 * crossed a gateway/proxy upstream); generates a fresh one otherwise. Registered first (lowest
 * filter order) so every other filter's own log lines are already covered.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String inbound = request.getHeader(HEADER_NAME);
        String correlationId = inbound == null || inbound.isBlank() ? UUID.randomUUID().toString() : inbound.trim();
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
