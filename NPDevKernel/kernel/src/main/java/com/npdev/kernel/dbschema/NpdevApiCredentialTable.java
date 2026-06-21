package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * Runtime-issued API key credentials, completing the tenant lifecycle: T4 (npdev_tenant) lets an
 * admin onboard a tenant without a regenerate, but the only way to AUTHENTICATE as that tenant was
 * still the startup-property {@code npdev.auth.api-keys} mapping (regenerate/restart required). This
 * table stores a SHA-256 hash of each issued key -- never the raw key, which is returned to the
 * caller exactly once at issuance time and is then unrecoverable.
 */
public final class NpdevApiCredentialTable {
    public static final String NAME = "npdev_api_credential";

    private NpdevApiCredentialTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("credential_id", TEXT),
                        InternalColumnDefinition.required("key_hash", TEXT),
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("actor_id", TEXT),
                        InternalColumnDefinition.required("roles", TEXT),
                        InternalColumnDefinition.required("status", TEXT),
                        InternalColumnDefinition.required("created_at_ms", BIGINT)
                ),
                InternalPrimaryKeyDefinition.of("credential_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_api_credential_hash", "key_hash"),
                        InternalIndexDefinition.index("idx_npdev_api_credential_tenant", "tenant_id")
                )
        );
    }
}
