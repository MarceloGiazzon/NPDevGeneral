package com.npdev.adapters.runtime.validation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > maxBodyBytes) {
            reject(response);
            return;
        }
        // W3.3 (2026-08-25 remediation plan / QUAL-33): a chunked-encoded (or otherwise
        // length-unknown) request reports getContentLengthLong() == -1, which is never >
        // maxBodyBytes, so the declared-length check alone let an arbitrarily large chunked body
        // through uninspected -- the exact payload-size DoS this filter exists to prevent. Reading
        // (at most maxBodyBytes + 1 bytes) up front, before the request reaches the controller,
        // enforces the same cap regardless of whether the client declared a length, and is safe to do
        // unconditionally here: every path this filter applies to (flow execute, event publish)
        // already expects a body this small.
        byte[] buffered = readUpToLimit(request.getInputStream(), maxBodyBytes);
        if (buffered == null) {
            reject(response);
            return;
        }
        filterChain.doFilter(new BufferedBodyRequestWrapper(request, buffered), response);
    }

    /** Reads at most {@code limit + 1} bytes. Returns null if the body exceeds {@code limit}. */
    private static byte[] readUpToLimit(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[limit + 1];
        int total = 0;
        int n;
        while (total < buffer.length && (n = in.read(buffer, total, buffer.length - total)) != -1) {
            total += n;
        }
        if (total > limit) {
            return null;
        }
        byte[] exact = new byte[total];
        System.arraycopy(buffer, 0, exact, 0, total);
        return exact;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"payload_too_large\",\"maxBodyBytes\":" + maxBodyBytes + "}");
    }

    /** Replays the already-buffered body so downstream consumers (Spring MVC's message converters)
     * see the same content the client sent, unaware the filter read it first. */
    private static final class BufferedBodyRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;

        BufferedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream delegate = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return delegate.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    // Synchronous replay only; this filter never runs in async servlet mode.
                }

                @Override
                public int read() {
                    return delegate.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return delegate.read(b, off, len);
                }
            };
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            // ServletRequestWrapper.getReader() defaults to the ORIGINAL request's reader, which
            // would read from an already-consumed stream (doFilterInternal already drained it via
            // getInputStream()) -- a real caller-visible regression for any consumer that reads the
            // body via getReader() rather than getInputStream(). Overriding both keeps them consistent.
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(body),
                    encoding != null ? encoding : StandardCharsets.UTF_8.name()));
        }
    }
}
