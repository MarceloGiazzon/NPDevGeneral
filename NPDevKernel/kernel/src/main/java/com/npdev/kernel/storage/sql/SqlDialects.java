package com.npdev.kernel.storage.sql;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * How a call site gets its dialect.
 *
 * <p><b>Why a resolved default and not constructor injection everywhere.</b> The engine is fixed for
 * the life of an app: it is chosen at generation time from the model's {@code DatabaseEngine} and
 * cannot change while the app runs. Threading a {@code SqlDialect} through the constructors of every
 * store would have touched the generator's wiring templates, the RuntimeHost's Spring configuration
 * and the generated-app assembly -- a large diff whose only purpose is to carry a value that is
 * constant. So each store takes an OPTIONAL dialect and falls back to {@link #active()}, which is
 * resolved once from configuration. Tests and the conformance suite inject explicitly.
 *
 * <p><b>Resolution order</b> (first match wins):
 * <ol>
 *   <li>the {@code npdev.storage.dialect} system property</li>
 *   <li>the {@code NPDEV_STORAGE_DIALECT} environment variable</li>
 *   <li>{@code postgres}</li>
 * </ol>
 *
 * <p>The default is {@code postgres} and not "detect from the connection" on purpose. Detection would
 * open a connection during construction, and -- more importantly -- it would make the emitted SQL
 * depend on runtime discovery rather than on the engine the app was GENERATED for, so a
 * misconfiguration would silently produce different SQL instead of failing. The two places that
 * genuinely must branch per-connection rather than per-app (cross-engine data promotion, and the H2
 * upsert probe that predates this class) keep their own detection and say so.
 */
public final class SqlDialects {

    private static final Map<String, SqlDialect> BY_NAME = Map.of(
            "postgres", PostgresDialect.INSTANCE,
            "postgresql", PostgresDialect.INSTANCE,
            "h2", H2Dialect.INSTANCE);

    /** System property that pins the dialect for a running app. */
    public static final String DIALECT_PROPERTY = "npdev.storage.dialect";

    /** Environment variable equivalent of {@link #DIALECT_PROPERTY}. */
    public static final String DIALECT_ENV = "NPDEV_STORAGE_DIALECT";

    private static volatile SqlDialect active;

    private SqlDialects() {
    }

    /**
     * The dialect this process speaks, resolved once.
     *
     * <p>Resolved lazily rather than in a static initialiser so a test that sets the system property
     * before first use still wins, and so a bad value fails at first storage access with a clear
     * message instead of during class loading with a {@code NoClassDefFoundError}.
     */
    public static SqlDialect active() {
        SqlDialect resolved = active;
        if (resolved == null) {
            synchronized (SqlDialects.class) {
                resolved = active;
                if (resolved == null) {
                    resolved = resolveFromConfiguration();
                    active = resolved;
                }
            }
        }
        return resolved;
    }

    /**
     * Pin the dialect explicitly, for a host that knows its engine (the RuntimeHost reads it from the
     * generated app's configuration at boot) and for tests.
     */
    public static void setActive(SqlDialect dialect) {
        if (dialect == null) {
            throw new IllegalArgumentException("dialect must not be null; use resetActiveForTesting() to clear");
        }
        active = dialect;
    }

    /** Drop the resolved dialect so the next {@link #active()} re-reads configuration. Tests only. */
    public static void resetActiveForTesting() {
        active = null;
    }

    /**
     * The dialect registered under {@code name}.
     *
     * @throws IllegalArgumentException listing the known names -- never a silent fallback to
     *         Postgres, which is how a typo in configuration becomes "the wrong engine, quietly"
     */
    public static SqlDialect forName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("storage dialect name must not be blank; known: " + knownNames());
        }
        SqlDialect dialect = BY_NAME.get(name.trim().toLowerCase(Locale.ROOT));
        if (dialect == null) {
            throw new IllegalArgumentException(
                    "unknown storage dialect '" + name + "'. Known: " + knownNames()
                    + ". A dialect must be registered in SqlDialects before an app can be generated for it.");
        }
        return dialect;
    }

    /** Every distinct registered dialect, for {@code npdev doctor}'s capability matrix. */
    public static List<SqlDialect> all() {
        return BY_NAME.values().stream().distinct()
                .sorted(java.util.Comparator.comparing(SqlDialect::name))
                .toList();
    }

    /**
     * The capability matrix, read out of the dialects themselves.
     *
     * <p>S3 requires this in {@code npdev doctor}, and requires it <b>generated from the code, never
     * maintained as a doc that drifts</b>. A hand-written table of which engine supports what is the
     * twin-pair defect family in its purest form: the day it disagrees with
     * {@link SqlDialect#capabilities()}, either a valid model is rejected or an impossible one is
     * accepted, and the table looks authoritative either way. So there is no table.
     *
     * <p>It lives here rather than beside the generator's capability gate so that rendering it needs
     * nothing but the kernel jar -- which every install already stages. A matrix a tool cannot print
     * without a full generator build is a matrix that quietly stops being printed.
     */
    public static String capabilityMatrix() {
        List<SqlDialect> dialects = all();
        StringBuilder out = new StringBuilder();
        int width = 22;
        out.append(String.format(Locale.ROOT, "%-" + width + "s", "capability"));
        for (SqlDialect dialect : dialects) {
            out.append(String.format(Locale.ROOT, "%-12s", dialect.name()));
        }
        out.append(System.lineSeparator());
        out.append("-".repeat(width + 12 * dialects.size())).append(System.lineSeparator());
        for (StorageCapability capability : StorageCapability.values()) {
            out.append(String.format(Locale.ROOT, "%-" + width + "s", capability));
            for (SqlDialect dialect : dialects) {
                out.append(String.format(Locale.ROOT, "%-12s",
                        dialect.capabilities().contains(capability) ? "yes" : "NO"));
            }
            out.append(System.lineSeparator());
        }
        return out.toString();
    }

    /** The same matrix as JSON, for a caller that renders rather than prints it. */
    public static String capabilityMatrixJson() {
        StringBuilder out = new StringBuilder();
        String nl = System.lineSeparator();
        out.append("{").append(nl);
        out.append("  \"schemaVersion\": \"npdev-storage-capability-matrix.v1\",").append(nl);
        out.append("  \"engines\": {").append(nl);
        List<SqlDialect> dialects = all();
        for (int d = 0; d < dialects.size(); d++) {
            SqlDialect dialect = dialects.get(d);
            out.append("    \"").append(dialect.name()).append("\": {").append(nl);
            StorageCapability[] every = StorageCapability.values();
            for (int c = 0; c < every.length; c++) {
                out.append("      \"").append(every[c]).append("\": ")
                        .append(dialect.capabilities().contains(every[c]))
                        .append(c == every.length - 1 ? "" : ",").append(nl);
            }
            out.append("    }").append(d == dialects.size() - 1 ? "" : ",").append(nl);
        }
        out.append("  }").append(nl).append("}").append(nl);
        return out.toString();
    }

    /**
     * Entry point {@code npdev doctor} runs to print the matrix.
     *
     * <p>Doctor shells out to this rather than carrying its own copy in Python. If the kernel jar is
     * not staged, doctor reports the section as unavailable and says why -- it never falls back to a
     * remembered table, because a remembered table is the thing being avoided.
     */
    public static void main(String[] args) {
        boolean json = args != null && args.length > 0 && "--json".equals(args[0]);
        System.out.print(json ? capabilityMatrixJson() : capabilityMatrix());
    }

    private static String knownNames() {
        return BY_NAME.keySet().stream().sorted().collect(Collectors.joining(", "));
    }

    private static SqlDialect resolveFromConfiguration() {
        String configured = System.getProperty(DIALECT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(DIALECT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return PostgresDialect.INSTANCE;
        }
        return forName(configured);
    }
}
