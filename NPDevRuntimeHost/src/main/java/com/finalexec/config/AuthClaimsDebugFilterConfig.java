package com.finalexec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs the claims resolved by RuntimeApiKeyAuthFilter for troubleshooting.
 * This helps confirm tenant/actor/roles at runtime (not what we assume).
 *
 * Remove after debugging.
 */
@Configuration
@Profile("dev")
public class AuthClaimsDebugFilterConfig {

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> authClaimsDebugFilter() {
        FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new AuthClaimsDebugFilter());
        bean.addUrlPatterns("/*");

        // Must run AFTER RuntimeApiKeyAuthFilter (generated filter often uses a very early order).
        // We keep it early but not the earliest.
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE - 50);

        return bean;
    }

    static final class AuthClaimsDebugFilter extends OncePerRequestFilter {
        private static final Logger log = LoggerFactory.getLogger(AuthClaimsDebugFilter.class);

        @Override
        protected void doFilterInternal(
                HttpServletRequest request,
                HttpServletResponse response,
                FilterChain filterChain
        ) throws ServletException, IOException {

            String uri = request.getRequestURI();
            boolean interesting =
                    uri != null && (uri.startsWith("/api/v1/flows/") || uri.startsWith("/api/flows/")
                            || uri.startsWith("/api/v1/traces") || uri.startsWith("/api/traces"));

            if (interesting) {
                Object claims = request.getAttribute("npdev.auth.claims");
                String apiKeyPresent =
                        (request.getHeader("X-API-Key") != null || request.getHeader("X-Api-Key") != null)
                                ? "present"
                                : "missing";

                log.info("AUTH DEBUG -> {} {} | X-API-Key={} | claims={}",
                        request.getMethod(), uri, apiKeyPresent, claims);
            }

            filterChain.doFilter(request, response);
        }
    }
}
