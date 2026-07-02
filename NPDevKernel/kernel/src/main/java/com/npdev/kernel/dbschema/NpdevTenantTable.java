package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * First-class registry of tenants, the backbone of runtime tenant lifecycle (hybrid multitenancy:
 * the permission MODEL stays signed/static, while tenant existence and membership are live data).
 * A tenant's {@code status} is the operational teeth: a DISABLED tenant is denied access at the
 * request boundary even though its credentials and identity rows still exist (suspension /
 * offboarding without destroying data).
 *
 * <p>{@code persistence_mode} is the live, per-tenant driver for adapter selection ("default" |
 * "audited"): unlike {@code persistence.adapter} (a generation-time, per-CONCEPT setting baked
 * into the constructor), this is real per-TENANT data an admin can flip at runtime with no
 * regenerate -- the generated service checks it on every request. Defaulted to "default" so every
 * existing tenant row (including ones from before this column existed) behaves exactly as before.</p>
 */
public final class NpdevTenantTable {
    public static final String NAME = "npdev_tenant";

    private NpdevTenantTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("tenant_id", TEXT),
                        InternalColumnDefinition.required("display_name", TEXT),
                        InternalColumnDefinition.required("status", TEXT),
                        InternalColumnDefinition.required("created_at_ms", BIGINT),
                        InternalColumnDefinition.defaulted("persistence_mode", TEXT, "'default'")
                ),
                InternalPrimaryKeyDefinition.of("tenant_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_tenant_status", "status")
                )
        );
    }
}
