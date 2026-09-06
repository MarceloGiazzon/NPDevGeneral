package com.finalexec.db;

import java.util.Locale;
import java.util.Optional;

/**
 * STOR-27 (B31 lift): H2 already solves the multi-process H2Local contention {@link
 * H2LocalBootLock} exists for -- {@code AUTO_SERVER=TRUE} makes the FIRST process to open a
 * {@code jdbc:h2:file:} database serve it over its own TCP endpoint; a later process connects
 * through that server instead of racing the raw file. This class decides, from the engine/URL
 * alone, whether to turn that parameter on. {@link H2LocalBootLockEnvironmentPostProcessor} is the
 * only caller, and calls it BEFORE the DataSource bean exists -- the one seam early enough to
 * rewrite the URL every later bean (Hikari included) will actually see.
 *
 * <p><b>Never touches a URL that already names {@code AUTO_SERVER}, either way.</b> That is what
 * keeps the four {@code MigrationKillMid*} test harnesses (which pin {@code AUTO_SERVER=FALSE}
 * explicitly, on purpose, to exercise the single-process embedded-file behavior
 * {@code H2LocalBootLock} still covers) working unchanged, and what lets an operator who wrote
 * {@code AUTO_SERVER=FALSE} by hand keep that choice.
 */
final class H2LocalAutoServer {

    private static final String ENGINE_H2LOCAL = "H2Local";
    private static final String JDBC_H2_FILE_PREFIX = "jdbc:h2:file:";
    private static final String AUTO_SERVER_TOKEN = "AUTO_SERVER=";
    private static final String AUTO_SERVER_TRUE_TOKEN = "AUTO_SERVER=TRUE";
    private static final java.util.regex.Pattern DB_CLOSE_ON_EXIT_FALSE_TOKEN =
            java.util.regex.Pattern.compile("(?i);DB_CLOSE_ON_EXIT=FALSE");

    /** Opt-out switch: {@code -Dnpdev.h2local.autoServer=false} keeps the OLD single-process,
     * boot-lock-guarded behavior even for a URL naming no {@code AUTO_SERVER} token at all. */
    private static final String ENABLED_PROPERTY = "npdev.h2local.autoServer";

    private H2LocalAutoServer() {
    }

    /** {@code true} unless explicitly opted out via {@code -Dnpdev.h2local.autoServer=false}. */
    static boolean enabledByDefault(org.springframework.core.env.ConfigurableEnvironment environment) {
        String configured = environment.getProperty(ENABLED_PROPERTY);
        return configured == null || configured.isBlank() || !"false".equalsIgnoreCase(configured.trim());
    }

    /**
     * The rewritten URL (with {@code ;AUTO_SERVER=TRUE} appended), or empty if nothing should
     * change: not an H2Local file URL, the URL already names {@code AUTO_SERVER} (either way), or
     * {@code autoServerEnabled} is false (the operator opted out).
     *
     * <p>Also strips a pre-existing {@code DB_CLOSE_ON_EXIT=FALSE} token, if present, before
     * appending {@code AUTO_SERVER=TRUE} -- H2 refuses that exact combination outright ("Feature
     * not supported: AUTO_SERVER=TRUE && DB_CLOSE_ON_EXIT=FALSE"), and every H2Local URL minted
     * before this class existed carries it (the generator's own pre-STOR-27 template). Without
     * this, every already-deployed H2Local app would fail to boot the moment this rewrite fired,
     * not just newly generated ones -- found live via {@code run-r10-plugin-controller-proof.py}
     * against a probe app whose stored URL still had the old shape.
     */
    static Optional<String> rewriteIfEligible(String engine, String jdbcUrl, boolean autoServerEnabled) {
        if (!autoServerEnabled || !ENGINE_H2LOCAL.equalsIgnoreCase(engine == null ? "" : engine.trim())) {
            return Optional.empty();
        }
        String trimmed = jdbcUrl == null ? "" : jdbcUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(JDBC_H2_FILE_PREFIX)) {
            return Optional.empty();
        }
        if (trimmed.toUpperCase(Locale.ROOT).contains(AUTO_SERVER_TOKEN)) {
            return Optional.empty();
        }
        String withoutDbCloseOnExitFalse = DB_CLOSE_ON_EXIT_FALSE_TOKEN.matcher(trimmed).replaceAll("");
        return Optional.of(withoutDbCloseOnExitFalse + ";" + AUTO_SERVER_TRUE_TOKEN);
    }

    /**
     * True when {@code jdbcUrl} explicitly names {@code AUTO_SERVER=TRUE} -- whether {@link
     * #rewriteIfEligible} just appended it, the generated app's own {@code application.properties}
     * minted it honestly (STOR-27, {@code UserDatabaseDefinitionLoader.jdbcUrl}), or an operator
     * wrote it by hand. {@link H2LocalBootLock} is skipped in every one of those cases: H2's own TCP
     * server, not this OS-level file lock, now arbitrates multi-process access.
     */
    static boolean isAutoServerActive(String jdbcUrl) {
        String trimmed = jdbcUrl == null ? "" : jdbcUrl.trim();
        return trimmed.toUpperCase(Locale.ROOT).contains(AUTO_SERVER_TRUE_TOKEN);
    }
}
