package com.npdev.dsl.v1.settings;

import java.util.List;

/**
 * The registry of NPDev behaviour settings. Each entry guarantees a platform default and is the
 * canonical id used in the config {@code defaults}/{@code overrides} envelope.
 *
 * <p>This is intentionally a small, growing vocabulary; new personalizable behaviours are added
 * here so there is one place that answers "what can be defaulted and overridden?". Defaults express
 * the platform's intended behaviour ("truthful and kernel-controlled by default; personalizable to
 * opt out"); the override envelope is how an app or a concept/field deviates from them.</p>
 */
public final class NpdevSettings {

    private NpdevSettings() {
    }

    /** Whether the default business Web UI is generated for persisted concepts. */
    public static final SettingKey<Boolean> UI_GENERATE_BUSINESS_UI =
            SettingKey.bool("ui.generateBusinessUi", true,
                    "Generate the default business Web UI for persisted concepts.");

    /** Whether generated CRUD routes execute through NPDev kernel control (permission/audit/events). */
    public static final SettingKey<Boolean> CRUD_KERNEL_CONTROLLED =
            SettingKey.bool("crud.kernelControlled", true,
                    "Route generated CRUD through kernel capability/permission/audit control.");

    /** How the generated app authenticates requests: apiKey|none|jwt. */
    public static final SettingKey<String> AUTH_MODE =
            SettingKey.string("auth.mode", "apiKey",
                    "How the app authenticates requests (apiKey|none|jwt).");

    /** Role that unlocks in-app admin / super-user surfaces (e.g. the internal NPDev tables). */
    public static final SettingKey<String> SECURITY_SUPER_USER_ROLE =
            SettingKey.string("security.superUserRole", "ADMIN",
                    "Role that unlocks in-app admin/super-user surfaces.");

    /** Whether tenant isolation is enforced on data access. */
    public static final SettingKey<Boolean> SECURITY_TENANT_ISOLATION =
            SettingKey.bool("security.tenantIsolation", true,
                    "Enforce tenant isolation on data access.");

    /** Whether the built-in NPDev internal tables (identity + workspace packs) are composed into the app. */
    public static final SettingKey<Boolean> INTERNAL_TABLES =
            SettingKey.bool("internal.tables", false,
                    "Compose the built-in NPDev internal tables (identity + workspace packs) into the generated app.");

    /** Whether custom code-bearing Coda extensions are allowed. */
    public static final SettingKey<Boolean> CODA_ALLOWED =
            SettingKey.bool("coda.allowed", false,
                    "Allow custom code-bearing Coda extensions to be mounted.");

    /** Whether application logging is enabled. */
    public static final SettingKey<Boolean> LOG_ENABLED =
            SettingKey.bool("log.enabled", true,
                    "Enable application logging.");

    /** Application log level: trace|debug|info|warn|error. */
    public static final SettingKey<String> LOG_LEVEL =
            SettingKey.string("log.level", "info",
                    "Application log level (trace|debug|info|warn|error).");

    /** UI input widget hint for a field (e.g. text, select, picker, slider, stars). Field-scope. */
    public static final SettingKey<String> FIELD_WIDGET =
            SettingKey.string("field.widget", "",
                    "UI input widget hint for a field.");

    private static final List<SettingKey<?>> ALL = List.of(
            UI_GENERATE_BUSINESS_UI,
            CRUD_KERNEL_CONTROLLED,
            AUTH_MODE,
            SECURITY_SUPER_USER_ROLE,
            SECURITY_TENANT_ISOLATION,
            INTERNAL_TABLES,
            CODA_ALLOWED,
            LOG_ENABLED,
            LOG_LEVEL,
            FIELD_WIDGET
    );

    /** All registered settings, in declaration order. */
    public static List<SettingKey<?>> all() {
        return ALL;
    }
}
