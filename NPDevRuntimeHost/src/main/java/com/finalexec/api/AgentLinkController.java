package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.agent.AgentChannelsStarter;
import com.finalexec.agent.AgentLinkService;
import com.finalexec.agent.TelegramChannel;
import com.finalexec.auth.JwtSigner;
import com.finalexec.auth.LoginController;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.runtime.support.IdentityRoleLookup;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.security.PrivateKey;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGENT-1 (A5.2, A6.3): endpoints an authenticated app user calls to manage their own agent
 * access -- minting an MCP token, and linking/unlinking Telegram/WhatsApp. The status probe lands
 * here too in A9; this class is already in {@code allowedControllers}.
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
    private final AgentLinkService linkService;
    private final AgentChannelsStarter channelsStarter;
    private final ObjectProvider<ExternalAiCapabilityContract> aiProvider;
    private final JwtSigner mcpTokenSigner;
    private final long mcpTokenExpirySeconds;
    private final String telegramBotUsername;
    private final String whatsappPhoneDisplay;

    public AgentLinkController(
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            DataSource dataSource,
            AgentLinkService linkService,
            AgentChannelsStarter channelsStarter,
            ObjectProvider<ExternalAiCapabilityContract> aiProvider,
            ObjectMapper objectMapper,
            @Value("${npdev.auth.jwt.private-key-path:}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${NPDEV_AGENT_MCP_TOKEN_DAYS:30}") long mcpTokenDays,
            @Value("${NPDEV_TELEGRAM_BOT_USERNAME:}") String telegramBotUsername,
            @Value("${NPDEV_WHATSAPP_PHONE_DISPLAY:}") String whatsappPhoneDisplay
    ) throws Exception {
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.dataSource = dataSource;
        this.linkService = linkService;
        this.channelsStarter = channelsStarter;
        this.aiProvider = aiProvider;
        this.telegramBotUsername = telegramBotUsername;
        this.whatsappPhoneDisplay = whatsappPhoneDisplay;
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
     * Creates a 10-minute link code for the calling user and returns the deep links a Telegram/
     * WhatsApp channel's connect button can offer -- {@code null} for a channel the model does not
     * enable (A1.11 already guarantees telegram/whatsapp enabled implies jwt mode + identity pack,
     * so this endpoint existing at all means those prerequisites hold).
     */
    @PostMapping("/api/agent/link-code")
    public ResponseEntity<Map<String, Object>> createLinkCode(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        AgentLinkService.CreatedCode created = linkService.createCode(context.tenantId(), context.actorId());

        var access = modelHolder.get().getAgentAccess();
        boolean telegramEnabled = access != null && access.getChannels() != null
                && access.getChannels().getTelegram().isEnabled();
        boolean whatsappEnabled = access != null && access.getChannels() != null
                && access.getChannels().getWhatsapp().isEnabled();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", created.code());
        body.put("expiresAt", DateTimeFormatter.ISO_INSTANT.format(created.expiresAt()));
        if (telegramEnabled && telegramBotUsername != null && !telegramBotUsername.isBlank()) {
            Map<String, Object> telegram = new LinkedHashMap<>();
            telegram.put("botUsername", telegramBotUsername);
            telegram.put("deepLink", "https://t.me/" + telegramBotUsername + "?start=" + created.code());
            body.put("telegram", telegram);
        } else {
            body.put("telegram", null);
        }
        if (whatsappEnabled && whatsappPhoneDisplay != null && !whatsappPhoneDisplay.isBlank()) {
            Map<String, Object> whatsapp = new LinkedHashMap<>();
            whatsapp.put("phoneDisplay", whatsappPhoneDisplay);
            whatsapp.put("linkCommand", "link " + created.code());
            body.put("whatsapp", whatsapp);
        } else {
            body.put("whatsapp", null);
        }
        return ResponseEntity.ok(body);
    }

    /** The current user's own linked channels. */
    @GetMapping("/api/agent/links")
    public ResponseEntity<Map<String, Object>> links(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        List<String> providers = linkService.linkedProviders(context.tenantId(), context.actorId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linked", new ArrayList<>(providers));
        return ResponseEntity.ok(body);
    }

    /**
     * Unlinks the given channel from the account linked to it, scoped to the CALLING user: the
     * provider/subject pair deleted must already resolve back to this user, so one user can never
     * unlink another's channel by guessing a provider name.
     */
    @DeleteMapping("/api/agent/links/{provider}")
    public ResponseEntity<Map<String, Object>> unlink(@PathVariable String provider, HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        boolean removed = linkService.unlinkForUser(provider, context.tenantId(), context.actorId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", removed);
        return ResponseEntity.ok(body);
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

    /**
     * AGENT-1 (A9.2): operator-facing status probe. SUPERUSER-only, same idiom
     * {@code AgentProxyController.requireSuperUser} uses. No secrets, no field whose name matches
     * the platform's redaction pattern (token|secret|apikey|...) -- {@code keyPresent} and
     * {@code botUsername} are the non-sensitive shapes those values take.
     */
    @GetMapping("/api/agent/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        requireSuperUser(request);
        var access = modelHolder.get().getAgentAccess();

        Map<String, Object> channels = new LinkedHashMap<>();
        boolean mcpEnabled = access != null && access.getChannels() != null && access.getChannels().getMcp().isEnabled();
        boolean telegramEnabled = access != null && access.getChannels() != null
                && access.getChannels().getTelegram().isEnabled();
        boolean whatsappEnabled = access != null && access.getChannels() != null
                && access.getChannels().getWhatsapp().isEnabled();
        channels.put("mcp", mcpEnabled);
        channels.put("telegram", telegramEnabled);
        channels.put("whatsapp", whatsappEnabled);

        TelegramChannel telegramChannel = channelsStarter == null ? null : channelsStarter.telegramChannel();
        Map<String, Object> telegram = new LinkedHashMap<>();
        telegram.put("running", telegramChannel != null && telegramChannel.isRunning());
        telegram.put("botUsername", telegramChannel == null ? "" : telegramChannel.botUsername());
        telegram.put("lastPollOk", telegramChannel == null || telegramChannel.lastPollOk() == null
                ? null : DateTimeFormatter.ISO_INSTANT.format(telegramChannel.lastPollOk()));

        ExternalAiCapabilityContract ai = aiProvider.getIfAvailable();
        List<ExternalAiVendorSummary> vendors = ai == null ? List.of() : ai.configuredVendors();
        Map<String, Object> aiStatus = new LinkedHashMap<>();
        aiStatus.put("vendor", vendors.stream().map(ExternalAiVendorSummary::vendorId).findFirst().orElse(""));
        aiStatus.put("keyPresent", vendors.stream().anyMatch(ExternalAiVendorSummary::keyPresent));

        Map<String, Object> linkedAccounts = new LinkedHashMap<>();
        linkedAccounts.put("telegram", linkService.linkedCount("telegram"));
        linkedAccounts.put("whatsapp", linkService.linkedCount("whatsapp"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channels", channels);
        body.put("telegram", telegram);
        body.put("ai", aiStatus);
        body.put("linkedAccounts", linkedAccounts);
        body.put("mcpUrl", baseUrl(request) + "/api/mcp");
        return ResponseEntity.ok(body);
    }

    private void requireSuperUser(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
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
