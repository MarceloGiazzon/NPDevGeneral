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
                        InternalColumnDefinition.required("created_at_ms", BIGINT)
                ),
                InternalPrimaryKeyDefinition.of("tenant_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_tenant_status", "status")
                )
        );
    }
}
