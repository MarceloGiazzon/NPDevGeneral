package com.npdev.kernel.dbschema;

import static com.npdev.kernel.dbschema.InternalColumnType.BIGINT;
import static com.npdev.kernel.dbschema.InternalColumnType.TEXT;

import java.util.List;

/**
 * Durable, deployment-independent record of "install this pack" requests made through a running
 * app's Store admin UI. Unlike the local-dev-only {@code Input/config.json} write (only reachable
 * when an {@code Input/} directory sits next to the deployed app's own working directory), this
 * table lives in the app's OWN database -- available to any deployment shape the app itself can
 * reach, the same way {@code npdev_tenant}/{@code npdev_api_credential} already are. It does not
 * make pack composition hot-reload: a pack's concepts only become real generated code after a real
 * regenerate+rebuild, same as every other change-as-data mechanism in this codebase. What it
 * upgrades is durability and cross-deployment visibility of the REQUEST itself -- an admin without
 * filesystem access to the generation-time Input/ directory can still see, and act on, "someone
 * asked for pack X."
 */
public final class NpdevPackInstallIntentTable {
    public static final String NAME = "npdev_pack_install_intent";

    private NpdevPackInstallIntentTable() {
    }

    public static InternalTableDefinition definition() {
        return new InternalTableDefinition(
                NAME,
                List.of(
                        InternalColumnDefinition.required("intent_id", TEXT),
                        InternalColumnDefinition.required("alias", TEXT),
                        InternalColumnDefinition.required("requested_at_ms", BIGINT),
                        InternalColumnDefinition.required("requested_by", TEXT),
                        InternalColumnDefinition.required("config_written", TEXT)
                ),
                InternalPrimaryKeyDefinition.of("intent_id"),
                List.of(
                        InternalIndexDefinition.index("idx_npdev_pack_install_intent_alias", "alias"),
                        InternalIndexDefinition.index("idx_npdev_pack_install_intent_ts", "requested_at_ms")
                )
        );
    }
}
