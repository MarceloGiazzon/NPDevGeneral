package com.npdev.kernel.ports;

import java.util.Optional;
import java.util.Set;

/**
 * Resolves a raw API key to a principal when the key is NOT one of the statically-baked,
 * generation-time mappings ({@code npdev.auth.api-keys}). This is the hook that lets a tenant be
 * onboarded with a working key at runtime, without a regenerate/restart: a RuntimeHost
 * implementation looks the key up (by hash) in a data-backed credential store.
 *
 * <p>The static mappings are checked FIRST and always win; this resolver is consulted only as a
 * fallback, so apps that never issue runtime credentials are completely unaffected.</p>
 */
public interface ApiKeyCredentialResolver {

    ApiKeyCredentialResolver NONE = apiKey -> Optional.empty();

    Optional<Principal> resolve(String apiKey);

    record Principal(String tenantId, String actorId, Set<String> roles) {
    }
}
