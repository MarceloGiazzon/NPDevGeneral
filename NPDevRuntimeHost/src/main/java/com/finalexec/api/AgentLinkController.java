package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.auth.JwtSigner;
import com.finalexec.auth.LoginController;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * AGENT-1 (A5.2): endpoints an authenticated app user calls to manage their own agent access --
 * today just minting an MCP token. Account linking (Telegram/WhatsApp) and the status probe land
 * here too in later phases (A6, A9); this class is already in {@code allowedControllers}.
 *
 * <p>jwt-mode only: an MCP token is itself a JWT, so this endpoint needs the same signing key
 * {@code OAuthGoogleController}/{@code LoginController} use for session tokens. apiKey/none apps
 * get no route here (the class never registers), matching {@code OAuthGoogleController}'s own
 * {@code @ConditionalOnProperty} gate.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class AgentLinkController {

    private static final long DEFAULT_MCP_TOKEN_DAYS = 30;

    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;
    private final DataSource dataSource;
    private final JwtSigner mcpTokenSigner;
    private final long mcpTokenExpirySeconds;

    public AgentLinkController(
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            DataSource dataSource,
            ObjectMapper objectMapper,
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${NPDEV_AGENT_MCP_TOKEN_DAYS:30}") long mcpTokenDays
    ) throws Exception {
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.dataSource = dataSource;
        long days = mcpTokenDays > 0 ? mcpTokenDays : DEFAULT_MCP_TOKEN_DAYS;
        this.mcpTokenExpirySeconds = days * 24 * 60 * 60;
        PrivateKey privateKey = (privateKeyPath == null || privateKeyPath.isBlank())
                ? null
                : JwtSigner.loadPrivateKey(LoginController.readKeyFile(
                        new DefaultResourceLoader(), privateKeyPath));
        this.mcpTokenSigner = privateKey == null
                ? null
                : new JwtSigner(objectMapper, privateKey, issuer, audience, mcpTokenExpirySeconds);
    }

    /**
     * Mints an MCP bearer token for the CALLING user, with their CURRENT roles and token version
     * (re-resolved from the identity pack, not copied from the inbound token's own claims -- "current"
     * is the point: a role granted after the caller's own session JWT was minted is reflected here).
     * Changing the user's password bumps {@code token_version}, which revokes every MCP token the same
     * way it revokes every session (existing behavior, reused -- not new).
     */
    @PostMapping("/api/agent/mcp-token")
    public ResponseEntity<Map<String, Object>> mintMcpToken(HttpServletRequest request) {
        if (mcpTokenSigner == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "no JWT signing key configured (npdev.auth.jwt.private-key-path)");
        }
        ExecutionContext context = runtimeContextService.currentContext(request);

        Set<String> roles = IdentityPackTableNames.tryResolve(modelHolder.get())
                .map(tables -> IdentityRoleLookup.rolesFor(dataSource, tables, context.tenantId(), context.actorId()))
                .orElseGet(() -> context.roles());
        int tokenVersion = IdentityPackTableNames.tryResolve(modelHolder.get())
                .map(tables -> IdentityRoleLookup.tokenVersion(dataSource, tables, context.tenantId(), context.actorId()))
                .orElse(0);

        String token = mcpTokenSigner.sign(context.tenantId(), context.actorId(), roles, tokenVersion);
        String baseUrl = baseUrl(request);
        String mcpUrl = baseUrl + "/api/mcp";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", token);
        body.put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(mcpTokenExpirySeconds)));
        body.put("mcpUrl", mcpUrl);
        body.put("claudeCodeCommand", "claude mcp add --transport http " + namespaceSlug()
                + " " + mcpUrl + " --header \"Authorization: Bearer " + token + "\"");
        return ResponseEntity.ok(body);
    }

    private String namespaceSlug() {
        String namespace = modelHolder.get().getNamespace();
        if (namespace == null || namespace.isBlank()) {
            return "npdev-app";
        }
        return namespace.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
    }

    private static String baseUrl(HttpServletRequest request) {
        StringBuilder url = new StringBuilder();
        url.append(request.getScheme()).append("://").append(request.getServerName());
        if ((request.getScheme().equals("http") && request.getServerPort() != 80)
                || (request.getScheme().equals("https") && request.getServerPort() != 443)) {
            url.append(':').append(request.getServerPort());
        }
        return url.toString();
    }
}
