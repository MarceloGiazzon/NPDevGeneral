package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.npdev.adapters.idp.github.GitHubIdentityProvider;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.security.PrivateKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The browser-facing half of the external identity-provider abstraction (SEC-11,
 * NPDEV_MEGA_ROADMAP.md Session 3b): the {@code authorize} -> provider -> {@code callback}
 * authorization-code round trip, plus a small {@code config} probe the generated login/signup
 * screens use to decide which "Continue with ..." buttons to render.
 *
 * <p>Provider-parameterized: this class name predates a second provider (originally Google-only)
 * and is kept rather than renamed, per this platform's own "don't rename casually" rule -- the
 * class's actual behavior is now provider-agnostic. Each configured provider gets its own route
 * segment ({@code /api/auth/oauth/google/...}, {@code /api/auth/oauth/github/...}); a provider with
 * no client id/secret configured simply has no entry in {@link #providers} and its routes 503.
 *
 * <p>Three browser flows land here, per provider:
 * <ul>
 *   <li><b>Sign up fresh with a provider</b>: {@code authorize?purpose=login} from the signup
 *       screen; the callback creates the {@code identity::User} + linkage and hands back a
 *       session.</li>
 *   <li><b>Log in with a provider thereafter</b>: the same {@code login} callback, now resolving
 *       via the stored linkage.</li>
 *   <li><b>Link a provider to an existing account</b>: {@code authorize?purpose=link} from a
 *       signed-in profile screen; the callback requires a valid session cookie (the JWT filter
 *       validates it before this controller runs) and only links after proving the provider's
 *       email is the account's own.</li>
 * </ul>
 *
 * <p>Credentials/state security: the state parameter is minted and single-use by
 * {@link OAuthStateStore} (CSRF for the redirect leg), shared across every provider -- the
 * provider itself is identified by the callback URL's own path segment, never by the state, so no
 * per-provider state scoping is needed. The client secret never enters the app's config beyond the
 * launcher-injected environment variable. The callback accepts {@code next} only as a same-origin
 * relative path (open-redirect guard).
 *
 * <p>When a given provider is not configured (no client id/secret, or a verify-only deployment
 * with no signing key) its routes report {@code oauth_not_configured}-shaped 503s rather than
 * failing cryptically -- the same degraded shape as {@link LoginController}'s verify-only mode.
 * {@code npdev.auth.oauth.<provider>.<endpoint>} overrides let a deployment point either flow at a
 * local fake provider (the live-verification harness), with each provider's real endpoints as the
 * defaults.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class OAuthGoogleController {

    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final String CALLBACK_PATH_TEMPLATE = "/api/auth/oauth/{provider}/callback";
    private static final String AUTHORIZE_PATH_TEMPLATE = "/api/auth/oauth/{provider}/authorize";

    private final OAuthStateStore stateStore;
    private final Map<String, IdentityProvider> providers;
    private final Map<String, OAuthGoogleAuthService> authServices;
    private final long expirySeconds;

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
            @Value("${npdev.auth.oauth.google.authorization-endpoint:https://accounts.google.com/o/oauth2/v2/auth}") String googleAuthorizationEndpoint,
            @Value("${npdev.auth.oauth.google.token-endpoint:https://oauth2.googleapis.com/token}") String googleTokenEndpoint,
            @Value("${npdev.auth.oauth.google.tokeninfo-endpoint:https://oauth2.googleapis.com/tokeninfo}") String googleTokenInfoEndpoint,
            @Value("${npdev.auth.oauth.github.client-id:}") String githubClientId,
            @Value("${npdev.auth.oauth.github.client-secret:}") String githubClientSecret,
            @Value("${npdev.auth.oauth.github.authorization-endpoint:https://github.com/login/oauth/authorize}") String githubAuthorizationEndpoint,
            @Value("${npdev.auth.oauth.github.token-endpoint:https://github.com/login/oauth/access_token}") String githubTokenEndpoint,
            @Value("${npdev.auth.oauth.github.user-endpoint:https://api.github.com/user}") String githubUserEndpoint,
            @Value("${npdev.auth.oauth.github.user-emails-endpoint:https://api.github.com/user/emails}") String githubUserEmailsEndpoint,
            @Value("${npdev.auth.oauth.state-ttl-seconds:600}") long stateTtlSeconds
    ) throws Exception {
        this.expirySeconds = expirySeconds;
        this.stateStore = new OAuthStateStore(Math.max(1L, stateTtlSeconds) * 1000L);

        PrivateKey privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(LoginController.readKeyFile(
                        new org.springframework.core.io.DefaultResourceLoader(), privateKeyPath));
        // No signing key -> no provider can mint a session, same "verify-only" degradation the
        // single-provider version had: every provider ends up simply absent from `providers` below.
        JwtSigner jwtSigner = privateKey == null
                ? null
                : new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds);
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        Map<String, IdentityProvider> providers = new LinkedHashMap<>();
        Map<String, OAuthGoogleAuthService> authServices = new LinkedHashMap<>();
        if (jwtSigner != null && present(googleClientId) && present(googleClientSecret)) {
            IdentityProvider google = new GoogleIdentityProvider(googleClientId, googleClientSecret,
                    googleAuthorizationEndpoint, googleTokenEndpoint, googleTokenInfoEndpoint, httpClient);
            providers.put(google.providerId(), google);
            authServices.put(google.providerId(), new OAuthGoogleAuthService(dataSource, modelHolder, google, jwtSigner));
        }
        if (jwtSigner != null && present(githubClientId) && present(githubClientSecret)) {
            IdentityProvider github = new GitHubIdentityProvider(githubClientId, githubClientSecret,
                    githubAuthorizationEndpoint, githubTokenEndpoint, githubUserEndpoint, githubUserEmailsEndpoint,
                    httpClient);
            providers.put(github.providerId(), github);
            authServices.put(github.providerId(), new OAuthGoogleAuthService(dataSource, modelHolder, github, jwtSigner));
        }
        this.providers = providers;
        this.authServices = authServices;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Package-private convenience for standalone unit tests: a single fully-wired provider,
     * pre-resolved. Production wiring flows through the Spring constructor above, which can wire
     * several providers side by side.
     */
    OAuthGoogleController(
            OAuthStateStore stateStore,
            IdentityProvider provider,
            OAuthGoogleAuthService authService,
            long expirySeconds
    ) {
        this.stateStore = stateStore;
        this.providers = Map.of(provider.providerId(), provider);
        this.authServices = Map.of(provider.providerId(), authService);
        this.expirySeconds = expirySeconds;
    }

    /** Every provider this app currently offers a "Continue with ..." button for. */
    @GetMapping("/api/auth/oauth/config")
    public ResponseEntity<Map<String, Object>> config() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String providerId : providers.keySet()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", providerId);
            entry.put("label", labelFor(providerId));
            entry.put("authorizePath", authorizePath(providerId));
            entries.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providers", entries);
        return ResponseEntity.ok(body);
    }

    @GetMapping(AUTHORIZE_PATH_TEMPLATE)
    public ResponseEntity<Void> authorize(
            @PathVariable String provider,
            @RequestParam(defaultValue = "login") String purpose,
            @RequestParam(defaultValue = "/") String next,
            HttpServletRequest request
    ) {
        IdentityProvider identityProvider = providers.get(provider);
        if (identityProvider == null) {
            return ResponseEntity.status(503).build();
        }
        String state = stateStore.create("link".equals(purpose) ? "link" : "login", sanitizeNext(next));
        String authorizationUrl = identityProvider.authorizationUrl(state, callbackUrl(request, provider));
        return ResponseEntity.status(HttpServletResponse.SC_FOUND)
                .header(HttpHeaders.LOCATION, authorizationUrl)
                .build();
    }

    @GetMapping(CALLBACK_PATH_TEMPLATE)
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            HttpServletRequest request
    ) {
        OAuthGoogleAuthService authService = authServices.get(provider);
        if (authService == null) {
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
            return handleLinkCallback(authService, code, target, request, provider);
        }
        OAuthGoogleAuthService.SessionTicket ticket =
                authService.resolveAndSignIn(code, callbackUrl(request, provider), "dev");
        if (ticket.token() == null) {
            return redirectWithError(target, ticket.errorCode());
        }
        return redirectWithSession(ticket, target, request);
    }

    private ResponseEntity<Void> handleLinkCallback(
            OAuthGoogleAuthService authService, String code, String target, HttpServletRequest request, String provider
    ) {
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
                authService.linkToAuthenticatedUser(code, callbackUrl(request, provider), tenant, actor);
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
        // SameSite=Lax, not Strict (REG-233): Lax still blocks this cookie from riding along on
        // cross-site subrequests and form posts -- CSRF protection is preserved -- but it permits
        // the cookie on a top-level GET navigation that lands here via a cross-site redirect, which
        // Strict does not. That distinction is required here specifically: the account-LINK leg of
        // this very flow (handleLinkCallback, above) depends on JwtBearerAuthFilter reading this
        // same npdev_jwt cookie back on the redirect FROM the provider to our own callback URL -- a
        // top-level navigation browsers treat as cross-site-initiated even though the destination
        // is same-origin. With Strict, the cookie set here on a prior login never rode along on
        // that hop and the link leg failed with oauth_link_requires_session every time.
        ResponseCookie sessionCookie = ResponseCookie.from("npdev_jwt", ticket.token())
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
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

    private static String authorizePath(String provider) {
        return "/api/auth/oauth/" + provider + "/authorize";
    }

    /** Display label for the login page's button text -- UI-only, so it stays out of the kernel port. */
    private static String labelFor(String providerId) {
        return switch (providerId) {
            case "google" -> "Google";
            case "github" -> "GitHub";
            default -> providerId;
        };
    }

    /**
     * The absolute callback URL this app serves for the given provider to redirect back to.
     * Derived from the inbound request so the value matches what the browser will actually land
     * on -- the provider keys the authorization by this exact value. No X-Forwarded handling: the
     * generated Caddy/compose proxy is out of scope for the callback URI (documented in SEC-11).
     */
    private static String callbackUrl(HttpServletRequest request, String provider) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme()).append("://").append(request.getServerName());
        if ((request.getScheme().equals("http") && request.getServerPort() != 80)
                || (request.getScheme().equals("https") && request.getServerPort() != 443)) {
            url.append(':').append(request.getServerPort());
        }
        return url.append("/api/auth/oauth/").append(provider).append("/callback").toString();
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
