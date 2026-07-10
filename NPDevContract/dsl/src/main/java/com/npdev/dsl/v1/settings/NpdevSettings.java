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
 *
 * <p>A setting belongs here only once something real reads it -- a registered key with no
 * consumer resolves and shows up in {@code resolved-settings.json} looking like a working feature
 * while doing nothing. {@code security.tenantIsolation} was removed for exactly this reason: tenant
 * isolation is enforced unconditionally by the kernel's {@code TenantIsolationPolicy}, and toggling
 * it off via this registry never actually disabled anything. Don't re-add a setting like it without
 * wiring a real consumer first.</p>
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

    /** Business UI shell mode for a section: full (header+sidenav, default) | minimal (no sidenav/header) | none (raw, author controls everything). Concept-scope. */
    public static final SettingKey<String> UI_FRAME_MODE =
            SettingKey.string("ui.frame.mode", "full",
                    "Business UI shell mode for a section (full|minimal|none).");

    /**
     * Named GuidePage (declared in the model's {@code guidePages}, or one of the built-in
     * Default/Minimal/None) assigned to a section. Concept-scope, cascading to app, then to the
     * empty string -- an empty resolution falls back to the {@code ui.frame.mode} mapping
     * (full/minimal/none -> Default/Minimal/None) so existing apps that only set frame mode are
     * unaffected. Read by {@code BusinessUiEmitter.resolveGuidePage}.
     */
    public static final SettingKey<String> UI_GUIDE_PAGE =
            SettingKey.string("ui.guidePage", "",
                    "Named GuidePage assigned to a section, overriding the ui.frame.mode mapping.");

    /**
     * Site-relative path of the app's login page. Empty (default) disables the shell's
     * unauthenticated redirect entirely, preserving the inline "not authenticated" notice. App-scope
     * only. Read by {@code BusinessUiEmitter} to populate the generated-ui-manifest.json auth block
     * consumed by shell.js's login redirect.
     */
    public static final SettingKey<String> AUTH_LOGIN_PATH =
            SettingKey.string("auth.loginPath", "",
                    "Site-relative path of the app's login page (empty disables shell auto-redirect).");

    /**
     * Persistence adapter variant for a concept's generated CRUD, overriding the model's declared
     * binding. Empty (default) = use the binding as declared, unchanged. Generation-time selection,
     * not a live per-request switch -- the resolved value is baked into the generated service when
     * the app is built, same as every other concept-scope setting in this registry.
     */
    public static final SettingKey<String> PERSISTENCE_ADAPTER =
            SettingKey.string("persistence.adapter", "",
                    "Persistence adapter variant for a concept's generated CRUD (\"\"|audited).");

    /**
     * Database provider for this app (e.g. h2-local|docker-postgres), app-scope only. This joins the
     * same defaults/overrides envelope and is recorded in resolved-settings.json like every other
     * setting, but it is honestly generation-time-only: the real consumer remains config.json's
     * existing flat {@code database.provider} field, read once by the generator when assembling the
     * app. There is no live runtime database switching -- this setting only makes the choice visible
     * and overridable through the same provenance mechanism as everything else.
     */
    public static final SettingKey<String> DATABASE_PROVIDER =
            SettingKey.string("database.provider", "",
                    "Database provider for this app (e.g. h2-local|docker-postgres). Generation-time only.");

    private static final List<SettingKey<?>> ALL = List.of(
            UI_GENERATE_BUSINESS_UI,
            CRUD_KERNEL_CONTROLLED,
            AUTH_MODE,
            SECURITY_SUPER_USER_ROLE,
            INTERNAL_TABLES,
            CODA_ALLOWED,
            LOG_ENABLED,
            LOG_LEVEL,
            FIELD_WIDGET,
            UI_FRAME_MODE,
            UI_GUIDE_PAGE,
            AUTH_LOGIN_PATH,
            PERSISTENCE_ADAPTER,
            DATABASE_PROVIDER
    );

    /** All registered settings, in declaration order. */
    public static List<SettingKey<?>> all() {
        return ALL;
    }
}
