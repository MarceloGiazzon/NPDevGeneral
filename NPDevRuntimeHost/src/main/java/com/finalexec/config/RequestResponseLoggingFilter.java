package com.finalexec.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

@Component
@Profile("dev")
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    // Avoid logging huge payloads
    private static final int MAX_CHARS = 4000;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Log only API calls (adjust if needed)
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(req, res);
        } finally {
            long ms = System.currentTimeMillis() - start;

            String method = req.getMethod();
            String uri = req.getRequestURI();
            String query = req.getQueryString();
            int status = res.getStatus();

            String requestBody = readRequestBody(req);
            String responseBody = readResponseBody(res);

            // NOTE: This may log PII (like email). Use carefully in production.
            LOG.debug("HTTP {} {}{} -> {} ({} ms)\nRequestBody: {}\nResponseBody: {}",
                    method,
                    uri,
                    (query == null ? "" : ("?" + query)),
                    status,
                    ms,
                    requestBody,
                    responseBody
            );

            // IMPORTANT: must copy body back to the real response
            res.copyBodyToResponse();
        }
    }

    private String readRequestBody(ContentCachingRequestWrapper req) {
        byte[] buf = req.getContentAsByteArray();
        if (buf == null || buf.length == 0) return "<empty>";

        Charset cs = getCharset(req.getCharacterEncoding());
        String s = new String(buf, cs);
        return truncate(s);
    }

    private String readResponseBody(ContentCachingResponseWrapper res) {
        byte[] buf = res.getContentAsByteArray();
        if (buf == null || buf.length == 0) return "<empty>";

        Charset cs = getCharset(res.getCharacterEncoding());
        String s = new String(buf, cs);
        return truncate(s);
    }

    private Charset getCharset(String enc) {
        try {
            if (enc != null) return Charset.forName(enc);
        } catch (Exception ignored) {}
        return StandardCharsets.UTF_8;
    }

    private String truncate(String s) {
        if (s == null) return "<null>";
        if (s.length() <= MAX_CHARS) return s;
        return s.substring(0, MAX_CHARS) + "...(truncated)";
    }
}
