package com.npdev.adapters.runtime.validation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class RuntimeRequestSizeFilter extends OncePerRequestFilter {
    // This max body limit is expected to be wired from application.properties through @Value or Environment-backed config.
    private final int maxBodyBytes;

    public RuntimeRequestSizeFilter(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes <= 0 ? 262_144 : maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        if (uri == null) {
            return true;
        }
        boolean flowExecute = uri.startsWith("/api/flows/") || uri.startsWith("/api/v1/flows/");
        boolean eventPublish = "/api/events/publish".equals(uri) || "/api/v1/events/publish".equals(uri);
        return !(flowExecute || eventPublish);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBodyBytes) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"payload_too_large\",\"maxBodyBytes\":" + maxBodyBytes + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
