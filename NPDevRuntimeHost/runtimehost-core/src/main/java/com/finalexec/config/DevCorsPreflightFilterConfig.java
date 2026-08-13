package com.finalexec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * CORS filter that runs BEFORE generated auth filters.
 *
 * Supports:
 * - http://localhost:* / http://127.0.0.1:* (local dev servers)
 * - Origin: null (file:// pages)
 *
 * SECURITY NOTE:
 * Allowing Origin "null" is for local dev only.
 */
@Configuration
@Profile("dev")
public class DevCorsPreflightFilterConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> devCorsPreflightFilter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new DevCorsPreflightFilter());
        bean.addUrlPatterns("/*");

        // Must run BEFORE RuntimeApiKeyAuthFilter
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE - 200);

        return bean;
    }

    public static class DevCorsPreflightFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {

            String origin = request.getHeader("Origin");

            if (origin != null && isAllowedOrigin(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");

                response.setHeader("Access-Control-Allow-Credentials", "false");
                response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");

                response.setHeader(
                        "Access-Control-Allow-Headers",
                        "Content-Type,Accept,X-API-Key,X-Api-Key,Authorization,X-Requested-With"
                );

                response.setHeader("Access-Control-Max-Age", "3600");
            }

            // Preflight: answer immediately
            if (isPreflight(request)) {
                response.setStatus(HttpServletResponse.SC_NO_CONTENT); // 204
                return;
            }

            filterChain.doFilter(request, response);
        }

        private static boolean isPreflight(HttpServletRequest request) {
            String method = request.getMethod();
            String acrm = request.getHeader("Access-Control-Request-Method");
            return "OPTIONS".equalsIgnoreCase(method) && acrm != null && !acrm.isBlank();
        }

        private static boolean isAllowedOrigin(String origin) {
            // Allow file:// pages (Chrome uses Origin: null)
            if ("null".equalsIgnoreCase(origin)) {
                return true;
            }

            String o = origin.toLowerCase(Locale.ROOT);
            return o.startsWith("http://localhost:") || o.startsWith("http://127.0.0.1:");
        }
    }
}
