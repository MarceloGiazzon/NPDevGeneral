package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * INT-8: per-credential policy for runtime-issued API keys -- a human label (which partner owns this
 * key), an optional expiry, and a last-used stamp so stale keys can be found and revoked. A SIDE
 * table keyed by {@code credential_id} rather than extra columns on {@link NpdevApiCredentialTable}:
 * nothing adds missing columns to an existing internal table, so a new column would never reach an
 * existing database, whereas a missing TABLE is created on boot. A credential with no row here (a key
 * issued before this table existed) has no label, never expires and has no last-used stamp yet.
 */
public final class NpdevApiCredentialPolicyTable {
    public static final String NAME = "npdev_api_credential_policy";

    private NpdevApiCredentialPolicyTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("credential_id", TEXT),
                        InternalColumnDefinition.optional("label", TEXT),
                        InternalColumnDefinition.optional("expires_at_ms", BIGINT),
                        InternalColumnDefinition.optional("last_used_at_ms", BIGINT),
                        InternalColumnDefinition.optional("created_by", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("credential_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_api_credential_policy_expiry", "expires_at_ms")
                )
        );
    }
}
