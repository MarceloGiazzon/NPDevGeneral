package com.finalexec.auth;

import com.npdev.adapters.idp.oidc.OidcIdentityProvider;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * SEC-9: parses the operator's OIDC provider configuration out of the Spring {@link Environment}
 * (so {@code NPDEV_AUTH_OIDC_<ID>_CLIENT_SECRET} works through relaxed binding with no extra code).
 * Configured purely by environment, never in {@code model.json}:
 *
 * <pre>
 * npdev.auth.oidc.providers=kc,okta
 * npdev.auth.oidc.kc.issuer / client-id / client-secret      (required)
 * npdev.auth.oidc.kc.scopes / label / email-claim / name-claim / picture-claim /
 *                       assume-email-verified / allowed-domains   (optional)
 * </pre>
 *
 * A provider with an invalid or reserved id, or a missing required key, is skipped with a WARNING
 * (never fatal: one bad provider must not stop the app from booting).
 */
public final class OidcProviderConfig {

    private static final Logger LOG = Logger.getLogger(OidcProviderConfig.class.getName());
    private static final Pattern ID = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Set<String> RESERVED = Set.of("google", "github", "telegram", "whatsapp", "mcp", "config");

    /** One configured provider: adapter settings, button label, and the optional email-domain allowlist. */
    public record Entry(OidcIdentityProvider.Settings settings, String label, Set<String> allowedDomains) {
    }

    private OidcProviderConfig() {
    }

    public static List<Entry> fromEnvironment(Environment env) {
        List<Entry> entries = new ArrayList<>();
        if (env == null) {
            return entries;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : splitList(env.getProperty("npdev.auth.oidc.providers", ""))) {
            String id = raw.toLowerCase(Locale.ROOT);
            if (!ID.matcher(id).matches()) {
                LOG.warning("Ignoring OIDC provider with invalid id '" + raw + "' (use a-z, 0-9, '-', max 40)");
                continue;
            }
            if (RESERVED.contains(id)) {
                LOG.warning("Ignoring OIDC provider '" + id + "': that id is reserved");
                continue;
            }
            if (!seen.add(id)) {
                continue;
            }
            String prefix = "npdev.auth.oidc." + id + ".";
            String issuer = trimToNull(env.getProperty(prefix + "issuer"));
            String clientId = trimToNull(env.getProperty(prefix + "client-id"));
            String clientSecret = trimToNull(env.getProperty(prefix + "client-secret"));
            if (issuer == null || clientId == null || clientSecret == null) {
                LOG.warning("Ignoring OIDC provider '" + id + "': issuer, client-id and client-secret are all required");
                continue;
            }
            OidcIdentityProvider.Settings settings = new OidcIdentityProvider.Settings(
                    id, issuer, clientId, clientSecret,
                    orDefault(env.getProperty(prefix + "scopes"), "openid email profile"),
                    orDefault(env.getProperty(prefix + "email-claim"), "email"),
                    orDefault(env.getProperty(prefix + "name-claim"), "name"),
                    orDefault(env.getProperty(prefix + "picture-claim"), "picture"),
                    Boolean.parseBoolean(orDefault(env.getProperty(prefix + "assume-email-verified"), "false")));
            entries.add(new Entry(settings, orDefault(env.getProperty(prefix + "label"), id),
                    domains(env.getProperty(prefix + "allowed-domains"))));
        }
        return entries;
    }

    /** Lower-cased comma list of email domains for {@code prefix} (e.g. the google/github allowlists). */
    public static Set<String> domains(String commaList) {
        Set<String> out = new LinkedHashSet<>();
        for (String domain : splitList(commaList)) {
            out.add(domain.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orDefault(String value, String fallback) {
        String trimmed = trimToNull(value);
        return trimmed == null ? fallback : trimmed;
    }
}
