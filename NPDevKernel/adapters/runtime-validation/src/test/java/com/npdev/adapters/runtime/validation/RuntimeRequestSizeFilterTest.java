package com.npdev.adapters.runtime.validation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRequestSizeFilterTest {

    @Test
    void shouldCoverApiAndApiV1ExecuteAndPublishPaths() {
        TestableRuntimeRequestSizeFilter filter = new TestableRuntimeRequestSizeFilter(1024);

        assertFalse(filter.publicShouldNotFilter(request("/api/flows/CreateUser/execute")));
        assertFalse(filter.publicShouldNotFilter(request("/api/v1/flows/CreateUser/execute")));
        assertFalse(filter.publicShouldNotFilter(request("/api/events/publish")));
        assertFalse(filter.publicShouldNotFilter(request("/api/v1/events/publish")));

        assertTrue(filter.publicShouldNotFilter(request("/api/v1/traces/summaries")));
        assertTrue(filter.publicShouldNotFilter(request("/health")));
    }

    @Test
    void requestAtLimitPassesAndRequestOverLimitReturns413WithStructuredError() throws Exception {
        TestableRuntimeRequestSizeFilter filter = new TestableRuntimeRequestSizeFilter(8);
        MockHttpServletRequest atLimit = request("/api/v1/events/publish");
        atLimit.setContent(new byte[8]);
        MockHttpServletResponse atLimitResponse = new MockHttpServletResponse();
        filter.doFilterInternal(atLimit, atLimitResponse, (req, res) -> { });
        assertTrue(atLimitResponse.getStatus() == 200 || atLimitResponse.getStatus() == 0);

        MockHttpServletRequest overLimit = request("/api/v1/events/publish");
        overLimit.setContent(new byte[9]);
        MockHttpServletResponse overLimitResponse = new MockHttpServletResponse();
        filter.doFilterInternal(overLimit, overLimitResponse, (req, res) -> { });
        assertEquals(413, overLimitResponse.getStatus());
        assertTrue(overLimitResponse.getContentAsString().contains("payload_too_large"));
    }

    // W3.3 (2026-08-25 remediation plan / QUAL-33): revived from @Disabled. The original stub's
    // rationale ("requires a full servlet container") does not hold for the length-unknown case --
    // MockHttpServletRequest can simulate a chunked body (no declared Content-Length) without one, and
    // doing so found a REAL gap: RuntimeRequestSizeFilter.doFilterInternal only ever checked
    // getContentLengthLong() > maxBodyBytes, and a chunked request reports getContentLengthLong() ==
    // -1, which is never > anything -- an oversized chunked body passed through uninspected. Fixed in
    // the same commit (buffers up to maxBodyBytes + 1 bytes regardless of declared length). The
    // "< 1ms overhead" performance expectation is dropped, not tested here: a hard wall-clock
    // assertion in a shared-CI-runner test is noise, not signal (see this repo's own standing rule on
    // cross-run perf comparisons never controlling for machine load).
    @Test
    void chunkedRequestWithNoDeclaredContentLengthIsStillSizeLimited() throws Exception {
        TestableRuntimeRequestSizeFilter filter = new TestableRuntimeRequestSizeFilter(8);

        MockHttpServletRequest overLimitChunked = request("/api/v1/events/publish");
        overLimitChunked.setContent(new byte[9]);
        overLimitChunked.setContentType("application/json");
        MockHttpServletResponse overLimitResponse = new MockHttpServletResponse();
        // Simulate what a real chunked-encoded body looks like at the servlet layer: the container
        // has already de-chunked the bytes, but declares no Content-Length at all.
        filter.doFilterInternal(withUnknownContentLength(overLimitChunked), overLimitResponse, (req, res) -> { });
        assertEquals(413, overLimitResponse.getStatus());
        assertTrue(overLimitResponse.getContentAsString().contains("payload_too_large"));

        MockHttpServletRequest atLimitChunked = request("/api/v1/events/publish");
        atLimitChunked.setContent(new byte[8]);
        HttpServletRequest atLimitChunkedRequest = withUnknownContentLength(atLimitChunked);
        MockHttpServletResponse atLimitResponse = new MockHttpServletResponse();
        AtomicReference<String> bodySeenDownstream = new AtomicReference<>();
        filter.doFilterInternal(atLimitChunkedRequest, atLimitResponse, (req, res) ->
                bodySeenDownstream.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
        assertTrue(atLimitResponse.getStatus() == 200 || atLimitResponse.getStatus() == 0);
        assertEquals(8, bodySeenDownstream.get().length(),
                "downstream must still see the full body after the filter buffered and replayed it");
    }

    @Test
    void webSocketUpgradeRequestsAreSkippedBecauseTheyNeverMatchTheGuardedPaths() {
        TestableRuntimeRequestSizeFilter filter = new TestableRuntimeRequestSizeFilter(1024);

        MockHttpServletRequest upgrade = request("/ws/runtime-events");
        upgrade.addHeader("Upgrade", "websocket");
        upgrade.addHeader("Connection", "Upgrade");

        assertTrue(filter.publicShouldNotFilter(upgrade),
                "a WebSocket upgrade never targets /api/(v1/)flows/*/execute or /api/(v1/)events/publish, "
                        + "so shouldNotFilter's existing path check already skips it -- this pins that fact");
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    /** MockHttpServletRequest derives getContentLength() from its stored body array with no way to
     * decouple them, so a real chunked request (body present, length unknown) is simulated with a
     * thin wrapper instead: same bytes via getInputStream(), -1 via both content-length accessors. */
    private static HttpServletRequest withUnknownContentLength(MockHttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
    }

    private static final class TestableRuntimeRequestSizeFilter extends RuntimeRequestSizeFilter {
        private TestableRuntimeRequestSizeFilter(int maxBodyBytes) {
            super(maxBodyBytes);
        }

        boolean publicShouldNotFilter(HttpServletRequest request) {
            return super.shouldNotFilter(request);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws ServletException, IOException {
            super.doFilterInternal(request, response, filterChain);
        }
    }
}
