package com.finalexec.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated api-key filter, driven directly. The grammar of {@code npdev.auth.api-keys} is
 * ApiKeyMappings' own (pinned by the kernel's ApiKeyMappingsTest); what is pinned here is what the
 * filter does with it: authenticate a configured key, fail closed for anything else, and say once at
 * startup how many entries it ignored (REG-319), without printing a key.
 */
class RuntimeApiKeyAuthFilterTest {

    private static final String CLAIMS_ATTRIBUTE = RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE;

    @Test
    void aConfiguredKeyAuthenticatesAndCarriesTenantActorAndRoles() throws Exception {
        RuntimeApiKeyAuthFilter filter = new RuntimeApiKeyAuthFilter("k=dev:alice:admin|ops;plain=dev:bob");

        MockHttpServletRequest request = apiRequest("k");
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest(), "the chain must continue");
        assertEquals(200, response.getStatus());
        Map<?, ?> claims = (Map<?, ?>) request.getAttribute(CLAIMS_ATTRIBUTE);
        assertEquals("dev", claims.get("tenant_id"));
        assertEquals("alice", claims.get("actor_id"));
        assertEquals(Set.of("ADMIN", "OPS"), claims.get("roles"));

        MockHttpServletRequest plain = apiRequest("plain");
        filter.doFilter(plain, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals(Set.of("USER"), ((Map<?, ?>) plain.getAttribute(CLAIMS_ATTRIBUTE)).get("roles"));
    }

    @Test
    void anUnknownOrMissingKeyIsRefusedAndTheChainDoesNotContinue() throws Exception {
        RuntimeApiKeyAuthFilter filter = new RuntimeApiKeyAuthFilter("k=dev:alice:ADMIN");

        MockHttpServletRequest unknown = apiRequest("nope");
        MockFilterChain unknownChain = new MockFilterChain();
        MockHttpServletResponse unknownResponse = new MockHttpServletResponse();
        filter.doFilter(unknown, unknownResponse, unknownChain);
        assertEquals(401, unknownResponse.getStatus());
        assertTrue(unknownResponse.getContentAsString().contains("invalid_api_key"));
        assertNull(unknownChain.getRequest(), "the chain must not continue");
        assertNull(unknown.getAttribute(CLAIMS_ATTRIBUTE));

        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse noneResponse = new MockHttpServletResponse();
        filter.doFilter(none, noneResponse, new MockFilterChain());
        assertEquals(401, noneResponse.getStatus());
        assertTrue(noneResponse.getContentAsString().contains("missing_api_key"));
    }

    @Test
    void anEntryTheGrammarDropsLocksOutOnlyThatKey() throws Exception {
        // key=ADMIN is the shape docs/CONFIGURATION.md used to describe: no tenant, no actor
        RuntimeApiKeyAuthFilter filter = new RuntimeApiKeyAuthFilter("broken=ADMIN;good=dev:alice:ADMIN");

        MockHttpServletResponse broken = new MockHttpServletResponse();
        filter.doFilter(apiRequest("broken"), broken, new MockFilterChain());
        assertEquals(401, broken.getStatus());

        MockHttpServletResponse good = new MockHttpServletResponse();
        filter.doFilter(apiRequest("good"), good, new MockFilterChain());
        assertEquals(200, good.getStatus());
    }

    @Test
    void blankMappingsFailClosedForEveryKey() throws Exception {
        for (String blank : new String[] {null, "", "  "}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RuntimeApiKeyAuthFilter(blank).doFilter(apiRequest("dev-key"), response, new MockFilterChain());
            assertEquals(401, response.getStatus(), "[" + blank + "]");
        }
    }

    @Test
    void theStaticMappingsWinAndTheCredentialResolverIsOnlyAFallback() throws Exception {
        ApiKeyCredentialResolver resolver = apiKey -> "runtime-key".equals(apiKey)
                ? Optional.of(new ApiKeyCredentialResolver.Principal("acme", "onboarded", Set.of("USER")))
                : Optional.of(new ApiKeyCredentialResolver.Principal("acme", "shadow", Set.of("USER")));
        RuntimeApiKeyAuthFilter filter = new RuntimeApiKeyAuthFilter("static=dev:alice:ADMIN", resolver);

        MockHttpServletRequest fromStatic = apiRequest("static");
        filter.doFilter(fromStatic, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("alice", ((Map<?, ?>) fromStatic.getAttribute(CLAIMS_ATTRIBUTE)).get("actor_id"));

        MockHttpServletRequest fromResolver = apiRequest("runtime-key");
        filter.doFilter(fromResolver, new MockHttpServletResponse(), new MockFilterChain());
        assertEquals("onboarded", ((Map<?, ?>) fromResolver.getAttribute(CLAIMS_ATTRIBUTE)).get("actor_id"));
    }

    @Test
    void aRequestAnEarlierFilterAlreadyAuthenticatedAndNonApiPagesAreNotFiltered() {
        RuntimeApiKeyAuthFilter filter = new RuntimeApiKeyAuthFilter("k=dev:alice:ADMIN");

        MockHttpServletRequest authenticated = apiRequest("stray");
        authenticated.setAttribute(CLAIMS_ATTRIBUTE, Map.of("actor_id", "earlier"));
        assertTrue(passesThroughUnfiltered(filter, authenticated), "an earlier filter's identity must not be clobbered");
        assertEquals(Map.of("actor_id", "earlier"), authenticated.getAttribute(CLAIMS_ATTRIBUTE));

        assertTrue(passesThroughUnfiltered(filter, new MockHttpServletRequest("GET", "/index.html")));
        assertTrue(passesThroughUnfiltered(filter, new MockHttpServletRequest("POST", "/api/hooks/payments")));
        assertTrue(passesThroughUnfiltered(filter, new MockHttpServletRequest("GET", "/api/public/menu")));
        assertFalse(passesThroughUnfiltered(filter, new MockHttpServletRequest("GET", "/api/orders")));
        assertFalse(passesThroughUnfiltered(filter, new MockHttpServletRequest("POST", "/api/public/menu")));
    }

    @Test
    void startupLogsHowManyEntriesWereIgnoredButNeverTheKey() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RuntimeApiKeyAuthFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new RuntimeApiKeyAuthFilter("s3cret-one=ADMIN;s3cret-two=tenant;good=dev:alice:ADMIN");
            new RuntimeApiKeyAuthFilter("good=dev:alice:ADMIN;");
        } finally {
            logger.detachAppender(appender);
        }

        List<ILoggingEvent> warnings = appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), "only the value with dropped entries warns: " + warnings);
        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains("2 entries"), message);
        assertTrue(message.contains("apiKey=tenant:actor[:ROLE[|ROLE...]]"), message);
        assertFalse(message.contains("s3cret"), "the log line must not leak a key: " + message);
    }

    private static MockHttpServletRequest apiRequest(String apiKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader("X-Api-Key", apiKey);
        return request;
    }

    /** shouldNotFilter is protected, so observe it: a skipped request reaches the chain with no 401 written. */
    private static boolean passesThroughUnfiltered(RuntimeApiKeyAuthFilter filter, MockHttpServletRequest request) {
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, response, chain);
            return chain.getRequest() == request && response.getStatus() == 200;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
