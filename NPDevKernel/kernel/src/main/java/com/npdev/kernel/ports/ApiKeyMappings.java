package com.npdev.kernel.ports;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The one parser of the {@code npdev.auth.api-keys} static mappings (REG-319). The generated
 * RuntimeApiKeyAuthFilter builds its lookup from {@link #parse} and StartupValidator calls it to
 * refuse a value that would lock every caller out, so the grammar exists exactly once. It is
 * documented in docs/CONFIGURATION.md ("apikey mode"); change both together.
 *
 * <p>Entries are separated by {@code ;}; each is {@code apiKey=tenant:actor[:ROLE[|ROLE...]]}. The api
 * key is everything before the first {@code =}. Tenant and actor are mandatory. Roles are upper-cased
 * and default to the single role {@code USER} when the third part is absent. An entry that does not fit
 * is dropped and counted, not rejected, so the filter fails closed (R7 Stage A) for that key alone.</p>
 *
 * <p>The api key is a secret: nothing here returns, logs or throws a rejected entry's text.</p>
 */
public final class ApiKeyMappings {

    /** What the grammar accepts, for error messages. */
    public static final String SHAPE = "apiKey=tenant:actor[:ROLE[|ROLE...]]";

    private ApiKeyMappings() {
    }

    /**
     * @param byApiKey the parsed entries, in declaration order (a repeated key keeps its last entry)
     * @param dropped  how many non-blank entries did not fit the grammar
     */
    public record Parsed(Map<String, ApiKeyCredentialResolver.Principal> byApiKey, int dropped) {
    }

    public static Parsed parse(String encodedMappings) {
        Map<String, ApiKeyCredentialResolver.Principal> parsed = new LinkedHashMap<>();
        int dropped = 0;
        String raw = normalize(encodedMappings);
        if (raw != null) {
            for (String entry : raw.split(";")) {
                String normalized = entry.trim();
                if (normalized.isBlank()) {
                    continue;
                }
                int equalsIndex = normalized.indexOf('=');
                if (equalsIndex <= 0 || equalsIndex >= normalized.length() - 1) {
                    dropped++;
                    continue;
                }
                String apiKey = normalize(normalized.substring(0, equalsIndex));
                ApiKeyCredentialResolver.Principal principal = parsePrincipal(normalized.substring(equalsIndex + 1));
                if (apiKey == null || principal == null) {
                    dropped++;
                    continue;
                }
                parsed.put(apiKey, principal);
            }
        }
        return new Parsed(Map.copyOf(parsed), dropped);
    }

    private static ApiKeyCredentialResolver.Principal parsePrincipal(String encoded) {
        String normalized = normalize(encoded);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split(":");
        String tenantId = parts.length > 0 ? normalize(parts[0]) : null;
        String actorId = parts.length > 1 ? normalize(parts[1]) : null;
        Set<String> roles = new LinkedHashSet<>();
        if (parts.length > 2) {
            for (String role : parts[2].split("\\|")) {
                String normalizedRole = normalize(role);
                if (normalizedRole != null) {
                    roles.add(normalizedRole.toUpperCase());
                }
            }
        } else {
            roles.add("USER");
        }
        if (tenantId == null || actorId == null || roles.isEmpty()) {
            return null;
        }
        return new ApiKeyCredentialResolver.Principal(tenantId, actorId, Set.copyOf(roles));
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
