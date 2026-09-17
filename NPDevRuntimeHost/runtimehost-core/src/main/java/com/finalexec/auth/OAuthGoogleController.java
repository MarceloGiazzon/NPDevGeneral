package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.adapters.idp.google.GoogleIdentityProvider;
import com.npdev.kernel.ports.IdentityProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The browser-facing half of the external identity-provider abstraction (SEC-11,
 * NPDEV_MEGA_ROADMAP.md Session 3b): the {@code authorize} -> provider -> {@code callback}
 * authorization-code round trip, plus a small {@code config} probe the generated login/signup
 * screens use to decide whether to render "Continue with Google".
 *
 * <p>Three browser flows land here:
 * <ul>
 *   <li><b>Sign up fresh with Google</b>: {@code authorize?purpose=login} from the signup screen;
 *       the callback creates the {@code identity::User} + linkage and hands back a session.</li>
 *   <li><b>Log in with Google thereafter</b>: the same {@code login} callback, now resolving via
 *       the stored linkage.</li>
 *   <li><b>Link Google to an existing account</b>: {@code authorize?purpose=link} from a
 *       signed-in profile screen; the callback requires a valid session cookie (the JWT filter
 *       validates it before this controller runs) and only links after proving the Google email
 *       is the account's own.</li>
 * </ul>
 *
 * <p>Credentials/state security: the state parameter is minted and single-use by
 * {@link OAuthStateStore} (CSRF for the redirect leg); the client secret never enters the app's
 * config beyond the launcher-injected environment variable. The callback accepts {@code next}
 * only as a same-origin relative path (open-redirect guard).
 *
 * <p>When OAuth is not configured (no client id/secret, or a verify-only deployment with no
 * signing key) every endpoint reports {@code oauth_not_configured} rather than failing
 * cryptically -- the same degraded shape as {@link LoginController}'s verify-only mode.
 * {@code npdev.auth.oauth.google.<endpoint>} overrides let a deployment point the flow at a local
 * fake provider (the live-verification harness), with Google's real endpoints as the defaults.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class OAuthGoogleController {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final String CALLBACK_PATH = "/api/auth/oauth/google/callback";
    private static final String AUTHORIZE_PATH = "/api/auth/oauth/google/authorize";

    private final DataSource dataSource;
    private final ModelHolder modelHolder;
    private final OAuthStateStore stateStore;
    private final IdentityProvider provider;
    private final OAuthGoogleAuthService authService;
    private final long expirySeconds;
    private final boolean configured;

    @Autowired
    public OAuthGoogleController(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ModelHolder modelHolder,
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${npdev.auth.jwt.expiry-seconds:28800}") long expirySeconds,
            @Value("${npdev.auth.oauth.google.client-id:}") String googleClientId,
            @Value("${npdev.auth.oauth.google.client-secret:}") String googleClientSecret,
            @Value("${npdev.auth.oauth.google.authorization-endpoint:https://accounts.google.com/o/oauth2/v2/auth}") String authorizationEndpoint,
            @Value("${npdev.auth.oauth.google.token-endpoint:https://oauth2.googleapis.com/token}") String tokenEndpoint,
            @Value("${npdev.auth.oauth.google.tokeninfo-endpoint:https://oauth2.googleapis.com/tokeninfo}") String tokenInfoEndpoint,
            @Value("${npdev.auth.oauth.state-ttl-seconds:600}") long stateTtlSeconds
    ) throws Exception {
        this.dataSource = dataSource;
        this.modelHolder = modelHolder;
        this.expirySeconds = expirySeconds;
        this.stateStore = new OAuthStateStore(Math.max(1L, stateTtlSeconds) * 1000L);

        boolean secretPresent = googleClientSecret != null && !googleClientSecret.isBlank();
        boolean clientPresent = googleClientId != null && !googleClientId.isBlank();
        if (secretPresent && clientPresent) {
            this.provider = new GoogleIdentityProvider(
                    googleClientId,
                    googleClientSecret,
                    authorizationEndpoint,
                    tokenEndpoint,
                    tokenInfoEndpoint,
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
        } else {
            this.provider = IdentityProvider.NONE;
        }
        PrivateKey privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(LoginController.readKeyFile(
                        new org.springframework.core.io.DefaultResourceLoader(), privateKeyPath));
        this.configured = privateKey != null && secretPresent && clientPresent;
        this.authService = this.configured
                ? new OAuthGoogleAuthService(dataSource, modelHolder, provider,
                        new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds))
                : null;
    }

    /**
     * Package-private convenience for standalone unit tests: fully-wired collaborators, provider
     * already resolved. Production wiring flows through the Spring constructor above.
     */
    OAuthGoogleController(
            OAuthStateStore stateStore,
            IdentityProvider provider,
            OAuthGoogleAuthService authService,
            long expirySeconds
    ) {
        this.dataSource = null;
        this.modelHolder = null;
        this.stateStore = stateStore;
        this.provider = provider;
        this.authService = authService;
        this.expirySeconds = expirySeconds;
        this.configured = true;
    }

    /** Whether this app currently offers Continue-with-Google (client + secret + signing key). */
    @GetMapping("/api/auth/oauth/config")
    public ResponseEntity<Map<String, Object>> config() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", configured);
        body.put("provider", provider.providerId());
        body.put("authorizePath", AUTHORIZE_PATH);
        return ResponseEntity.ok(body);
    }

    @GetMapping(AUTHORIZE_PATH)
    public ResponseEntity<Void> authorize(
            @RequestParam(defaultValue = "login") String purpose,
            @RequestParam(defaultValue = "/") String next,
            HttpServletRequest request
    ) {
        if (!configured) {
            return ResponseEntity.status(503).build();
        }
        String state = stateStore.create("link".equals(purpose) ? "link" : "login", sanitizeNext(next));
        String authorizationUrl = provider.authorizationUrl(state, callbackUrl(request));
        return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                .header(HttpHeaders.LOCATION, authorizationUrl)
                .build();
    }

    @GetMapping(CALLBACK_PATH)
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            HttpServletRequest request
    ) {
        if (!configured) {
            return ResponseEntity.status(503).build();
        }
        if (code == null || code.isBlank()) {
            return redirectWithError("/", "oauth_provider_refused");
        }
        OAuthStateStore.Pending pending = stateStore.consume(state).orElse(null);
        if (pending == null) {
            return redirectWithError("/", "oauth_state_invalid");
        }
        String target = sanitizeNext(pending.next());

        if ("link".equals(pending.purpose())) {
            return handleLinkCallback(code, target, request);
        }
        OAuthGoogleAuthService.SessionTicket ticket =
                authService.resolveAndSignIn(code, callbackUrl(request), "dev");
        if (ticket.token() == null) {
            return redirectWithError(target, ticket.errorCode());
        }
        return redirectWithSession(ticket, target, request);
    }

    private ResponseEntity<Void> handleLinkCallback(String code, String target, HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> claims = (Map<String, Object>) request.getAttribute(CLAIMS_ATTRIBUTE);
        if (claims == null || claims.isEmpty()) {
            return redirectWithError(target, "oauth_link_requires_session");
        }
        String actor = claimAsString(claims, "actor_id");
        if (actor == null) {
            actor = claimAsString(claims, "sub");
        }
        String tenant = claimAsString(claims, "tenant_id");
        if (tenant == null) {
            tenant = "dev";
        }
        OAuthGoogleAuthService.SessionTicket ticket =
                authService.linkToAuthenticatedUser(code, callbackUrl(request), tenant, actor);
        if (ticket.outcome() == OAuthGoogleAuthService.Outcome.LINKED) {
            return redirectWithError(target, null);
        }
        return redirectWithError(target, ticket.errorCode());
    }

    private ResponseEntity<Void> redirectWithSession(
            OAuthGoogleAuthService.SessionTicket ticket,
            String target,
            HttpServletRequest request
    ) {
        ResponseCookie sessionCookie = ResponseCookie.from("npdev_jwt", ticket.token())
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(expirySeconds)
                .build();
        return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                .header(HttpHeaders.SET_COOKIE, sessionCookie.toString())
                .header(HttpHeaders.LOCATION, target)
                .build();
    }

    private ResponseEntity<Void> redirectWithError(String target, String errorCode) {
        String location = target + (errorCode == null || errorCode.isBlank() ? "" : "?error=" + errorCode);
        return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                .header(HttpHeaders.LOCATION, location)
                .build();
    }

    /**
     * Open-redirect guard for the {@code next} parameter: only same-origin relative paths are
     * accepted. Anything else (absolute URL, protocol-relative, backslash tricks) falls back to
     * the app root. Static so the test suite can pin it directly.
     */
    static String sanitizeNext(String next) {
        if (next == null) {
            return "/";
        }
        String candidate = next.trim();
        boolean relative = candidate.startsWith("/")
                && !candidate.startsWith("//")
                && !candidate.contains("://")
                && !candidate.contains("\\");
        return relative ? candidate : "/";
    }

    /**
     * The absolute callback URL this app serves for the provider to redirect back to. Derived from
     * the inbound request so the value matches what the browser will actually land on -- the
     * provider keys the authorization by this exact value. No X-Forwarded handling: the generated
     * Caddy/compose proxy is out of scope for the callback URI (documented in SEC-11).
     */
    private static String callbackUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme()).append("://").append(request.getServerName());
        if ((request.getScheme().equals("http") && request.getServerPort() != 80)
                || (request.getScheme().equals("https") && request.getServerPort() != 443)) {
            url.append(':').append(request.getServerPort());
        }
        return url.append(CALLBACK_PATH).toString();
    }

    private static String claimAsString(Map<String, Object> claims, String key) {
        if (claims == null || key == null) {
            return null;
        }
        Object value = claims.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}