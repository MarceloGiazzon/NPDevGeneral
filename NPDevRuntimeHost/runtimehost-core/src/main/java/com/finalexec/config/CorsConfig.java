package com.finalexec.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for local testing.
 * Allows browser pages served from http://localhost:5500 to call this app on http://localhost:8080.
 */
@Configuration
@Profile("dev")
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {

        registry.addMapping("/**")
                .allowedOriginPatterns(
                        "http://localhost:*",
                        "http://127.0.0.1:*"
                )
                .allowedMethods(
                        "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
                )
                .allowedHeaders(
                        "Content-Type",
                        "Accept",
                        "X-API-Key",
                        "Authorization",
                        "X-Requested-With"
                )
                .exposedHeaders(
                        "Location"
                )
                .allowCredentials(false)
                .maxAge(3600);
    }
}
