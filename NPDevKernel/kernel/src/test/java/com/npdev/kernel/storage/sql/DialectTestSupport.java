package com.npdev.kernel.storage.sql;

import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands out a connection for the behavioural conformance tiers -- <b>without Docker.</b>
 *
 * <p>Local Docker is banned on this project's dev machine and its RAM was halved, but 16 of the 20
 * behavioural vectors only need "a database and a table". This gives one, backed by H2 in the target
 * engine's compatibility MODE locally and by a real container in CI. <b>The test never knows which it
 * got</b> -- that is what lets one suite serve both.
 *
 * <p>A sibling of the existing {@code PostgresTestSupport}, which uses Testcontainers and stays as it
 * is; replacing it would trade a working Docker-based suite for an approximation.
 *
 * <h2>Be honest about what H2-in-MySQL-mode proves</h2>
 *
 * <p>It catches SYNTAX and SHAPE. It does not enforce everything a real engine does, and this
 * codebase has already learned that once with H2-in-PostgreSQL-mode (REG-36, REG-50: an adapter's
 * fallback needed a real Postgres to prove against). <b>Local H2 is the fast signal; the CI container
 * is the true one. Never claim engine support from a green local run alone.</b>
 *
 * <p>{@link #enforces} is how a vector says so out loud. A check H2 cannot be trusted for is
 * REPORTED as not-verified-here, never quietly passed -- a suite that reports green for checks it did
 * not really run is the silent-answer defect wearing a test's clothes.
 */
public final class DialectTestSupport {

    /** Set {@code NPDEV_DIALECT_BACKEND=container} in CI. Anything else (or unset) means local H2. */
    private static final String BACKEND =
            System.getenv().getOrDefault("NPDEV_DIALECT_BACKEND", "h2");

    /** Each call gets its own database; a shared one is how a suite starts passing only in order. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    /** One container per engine per JVM. Started lazily so a local run never touches Docker. */
    private static final Map<String, JdbcDatabaseContainer<?>> CONTAINERS = new ConcurrentHashMap<>();

    private DialectTestSupport() {
    }

    /**
     * Images this suite knows how to start, by dialect name. <b>The single source of truth for "can
     * the container backend serve this dialect".</b>
     *
     * <p>h2 is deliberately absent: it IS the local backend and has no container. Run 31264977219
     * -- the first real execution of the CI suite -- reported 13 failures per job purely because the
     * parameter source handed h2 to a container-only run. All 13 threw
     * {@code IllegalArgumentException} in 0.1s total, against no database at all, which is what
     * distinguished them from the single real behavioural failure.
     *
     * <h2>PINNED TO DIGESTS, and why that came before promoting the CI trigger</h2>
     *
     * <p>These were moving tags ({@code mysql:8.4}, {@code postgres:16},
     * {@code mcr.microsoft.com/mssql/server:2022-latest}) until 2026-08-08. A push-blocking gate on a
     * moving tag <b>cannot tell "we broke it" from "the image changed"</b>, and a gate people cannot
     * trust is a gate they re-run instead of read. So the digests were pinned FIRST and the trigger
     * promoted second -- {@code storage/FULL_SUPPORT_PLAN.md} W1.1 before W1.2.
     *
     * <p><b>Digest ONLY -- the tag cannot be glued on, and finding that out cost one red test rather
     * than one red CI job.</b> {@code repository:tag@sha256:...} is valid Docker syntax and the
     * obvious way to keep the human-readable version beside the immutable identity. Testcontainers
     * 1.21.4 does not parse it that way: {@code DockerImageName.parse("mysql:8.4@sha256:...")}
     * splits on {@code @} only, so the repository comes back as {@code "mysql:8.4"}, which then
     * fails {@code MySQLContainer}'s own {@code assertCompatibleWith(mysql)} <b>at container
     * construction</b>. That failure would have landed thirty seconds into a CI job, inside a forked
     * test JVM whose stdout Gradle does not forward. {@link DialectContainerImagePinningTest} asserts
     * the parse instead, in milliseconds, with no Docker -- it is the test that caught this.
     *
     * <p>The tag each digest was resolved from is therefore kept as DATA in
     * {@link #CONTAINER_IMAGE_TAGS} rather than as a comment, so {@code --resolve} can re-read the
     * registry from it and a stale note cannot masquerade as a pin.
     *
     * <p><b>Refresh quarterly.</b> A digest that is never refreshed becomes its own problem -- CVEs
     * accumulate and a pinned EOL image is a different kind of stale than a moving one. Re-resolve
     * with {@code scripts/quality/check-container-images-pinned.py --resolve}, which reads the
     * registry and prints the current digest for each tag, then update BOTH this map and the
     * workflow's {@code PINNED_*} env block -- the checker fails the gate if they disagree.
     *
     * <pre>
     *   pinned 2026-08-08   refresh due 2026-11-08
     * </pre>
     */
    private static final Map<String, String> CONTAINER_IMAGES = Map.of(
            "mysql",
            "mysql@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb",
            "postgres",
            "postgres@sha256:95206741a5b214807675e14165369d05b93a9cf692223b616d07cca227e74b0b",
            "sqlserver",
            "mcr.microsoft.com/mssql/server"
            + "@sha256:ba4c8329f48fb8f02e1416be6a930ebfd71268caee78aa985f3af4315e457c89");

    /**
     * The tag each digest in {@link #CONTAINER_IMAGES} was resolved FROM, on the pin date.
     *
     * <p>Not documentation: {@code --resolve} re-reads the registry through these, so the refresh
     * path cannot drift from what was actually pinned. A digest with no recorded tag is a digest
     * nobody can refresh without archaeology.
     */
    private static final Map<String, String> CONTAINER_IMAGE_TAGS = Map.of(
            "mysql", "mysql:8.4",
            "postgres", "postgres:16",
            "sqlserver", "mcr.microsoft.com/mssql/server:2022-latest");

    /**
     * The pinned images, for the test that asserts they stay pinned and parseable.
     *
     * <p>Exposed rather than duplicated: a test that carries its own copy of the map proves the copy
     * is well-formed, which is not the question.
     */
    public static Map<String, String> containerImages() {
        return CONTAINER_IMAGES;
    }

    /** The tag each pinned digest came from. See {@link #CONTAINER_IMAGE_TAGS}. */
    public static Map<String, String> containerImageTags() {
        return CONTAINER_IMAGE_TAGS;
    }

    /**
     * System property CI sets so ONE job means ONE engine: {@code -Dnpdev.dialect.only=mysql}.
     *
     * <p>Before this existed the matrix selected jobs but the test class was parameterised over
     * every dialect, so each job started three containers, verified MySQL three times, and reported
     * SQL Server's failure three times. A job's red/green said nothing about the engine in its name.
     */
    public static final String ONLY_PROPERTY = "npdev.dialect.only";

    /**
     * Whether {@code dialect} should run on the CURRENT backend, honouring {@link #ONLY_PROPERTY}.
     *
     * <p>Two independent filters, deliberately kept separate:
     * <ul>
     *   <li><b>backend capability</b> -- container mode can only serve a dialect with a registered
     *       image. Locally every dialect is servable (H2 impersonates each one).</li>
     *   <li><b>explicit scoping</b> -- {@code -Dnpdev.dialect.only}, unset locally.</li>
     * </ul>
     *
     * @throws IllegalArgumentException when {@code npdev.dialect.only} names a dialect that is not
     *         registered. A typo must NOT quietly select nothing and let the suite report green on
     *         zero tests -- that is strictly worse than the bug this filter fixes, because it looks
     *         like proof.
     */
    public static boolean shouldRun(SqlDialect dialect) {
        String only = System.getProperty(ONLY_PROPERTY, "").trim();
        if (!only.isEmpty()) {
            SqlDialects.forName(only); // throws, listing the known names, if it is a typo
            if (!only.equalsIgnoreCase(dialect.name())) {
                return false;
            }
        }
        return !isContainerBacked() || CONTAINER_IMAGES.containsKey(dialect.name());
    }

    public static boolean isContainerBacked() {
        return "container".equalsIgnoreCase(BACKEND);
    }

    /**
     * An ISOLATED, EMPTY database speaking {@code dialect}.
     *
     * <p>Isolated per call on purpose: vectors must not depend on each other's leftovers. A shared
     * schema is how a suite starts passing only in the order it happens to run in, which is
     * indistinguishable from a real bug when it eventually fails.
     */
    public static Connection connectionFor(SqlDialect dialect) throws SQLException {
        if (!isContainerBacked()) {
            return DriverManager.getConnection(h2Url(dialect.name()));
        }
        JdbcDatabaseContainer<?> container = containerFor(dialect);
        // A FRESH SCHEMA per call, not a fresh container: starting one container per test would put
        // minutes on every CI run for no isolation the schema does not already give.
        Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        resetSchema(connection, dialect);
        return connection;
    }

    /**
     * One container per engine per JVM, started on first use and reused.
     *
     * <p>Mirrors {@code PostgresTestSupport}'s lifecycle deliberately: a container per TEST would
     * dominate the CI clock, and Ryuk reaps these when the JVM exits.
     */
    private static synchronized JdbcDatabaseContainer<?> containerFor(SqlDialect dialect) {
        return CONTAINERS.computeIfAbsent(dialect.name(), name -> {
            String image = CONTAINER_IMAGES.get(name);
            if (image == null) {
                // STILL A THROW, and deliberately so even though shouldRun() now filters these out
                // before they reach here. This is the backstop for the bug it was written for: a
                // dialect added to SqlDialects and never given an image. Without it that dialect
                // would silently fall back to H2 while the job name said "real engine" -- a green
                // tick over an approximation. The filter is the ergonomics; this is the safety.
                throw new IllegalArgumentException(
                        "no container image registered for dialect '" + name + "' (h2 has none by "
                        + "design -- it IS the local backend). Add one to CONTAINER_IMAGES and to the "
                        + "CI workflow matrix, or the vector silently never runs against the real "
                        + "engine while the suite reports green.");
            }
            JdbcDatabaseContainer<?> started = switch (name) {
                case "mysql" -> new MySQLContainer<>(DockerImageName.parse(image))
                        // utf8mb4 is not optional: MySQL's legacy three-byte "utf8" silently mangles
                        // anything outside the BMP, so a default container would make conformance J2
                        // fail for a reason that has nothing to do with the dialect.
                        .withCommand("--character-set-server=utf8mb4",
                                     "--collation-server=utf8mb4_unicode_ci");
                case "postgres" -> new PostgreSQLContainer<>(DockerImageName.parse(image));
                case "sqlserver" -> new MSSQLServerContainer<>(DockerImageName.parse(image))
                        .acceptLicense();
                default -> throw new IllegalArgumentException(
                        "dialect '" + name + "' has an image registered but no container type here -- "
                        + "the two halves of the mapping have drifted.");
            };
            // F5: one line per container start. Before this, the console log had ZERO mentions of
            // Testcontainers, Docker, Ryuk or any image name -- whether a container had started at
            // all had to be INFERRED from the fact that the h2 path threw. The failure this guards
            // against is a backend silently falling back to H2 while the job says "real engine", and
            // one printed line makes that impossible to miss.
            long startedAt = System.nanoTime();
            started.start();
            System.out.printf("[dialect-support] started %s for '%s' -> %s (%.1fs)%n",
                    image, name, started.getJdbcUrl(), (System.nanoTime() - startedAt) / 1_000_000_000.0);
            return started;
        });
    }

    /**
     * Drop everything the previous test left behind.
     *
     * <p>Vectors must not depend on each other's leftovers. Locally that is free -- each call gets a
     * new in-memory database -- so this exists to give the container path the SAME guarantee, rather
     * than letting CI be the one place where test order matters.
     */
    private static void resetSchema(Connection connection, SqlDialect dialect) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getTables(
                connection.getCatalog(), connection.getSchema(), "%", new String[] {"TABLE"})) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME"));
            }
        }
        for (String table : tables) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + dialect.quoteIdentifier(table));
            } catch (SQLException ignored) {
                // A table another table references cannot be dropped first. The second pass below
                // catches those; a table that survives both is reported by the vector that trips
                // over it, which is a better message than anything this loop could produce.
            }
        }
        for (String table : tables) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + dialect.quoteIdentifier(table));
            } catch (SQLException ignored) {
                // as above
            }
        }
    }

    private static String h2Url(String dialectName) {
        String mode = switch (dialectName.toLowerCase(Locale.ROOT)) {
            case "mysql" -> "MySQL";
            case "sqlserver" -> "MSSQLServer";
            case "postgres", "postgresql" -> "PostgreSQL";
            case "h2" -> null;
            default -> throw new IllegalArgumentException(
                    "unknown dialect '" + dialectName + "' -- add it here AND to SqlDialects, or a typo "
                    + "silently becomes plain H2 and the vector proves nothing");
        };
        return "jdbc:h2:mem:dialect_" + dialectName + "_" + SEQUENCE.incrementAndGet()
                + ";DB_CLOSE_DELAY=-1"
                + (mode == null ? "" : ";MODE=" + mode + ";DATABASE_TO_LOWER=TRUE");
    }

    /**
     * True when this backend enforces {@code behaviour} faithfully enough to assert on it.
     *
     * <p><b>Use this instead of skipping silently.</b> Prefer {@code assumeTrue(enforces(...))} so the
     * runner records a skip WITH ITS REASON rather than a pass.
     */
    public static boolean enforces(SqlDialect dialect, Behaviour behaviour) {
        if (isContainerBacked()) {
            return true; // the real engine enforces what it claims to
        }
        return switch (behaviour) {
            // H2 honours these well enough in compatibility mode.
            case SYNTAX_SHAPE, IDENTIFIER_QUOTING, PAGINATION, UNIQUENESS, DML_TRANSACTIONALITY -> true;
            // H2 does NOT faithfully reproduce these. CI, against the real engine, or not at all.
            case DDL_TRANSACTIONALITY,     // MySQL commits implicitly on DDL; H2-in-MySQL-mode does not model it
                 NATIVE_UPSERT_SEMANTICS,  // ON DUPLICATE KEY vs MERGE concurrency behaviour
                 CATALOG_INTROSPECTION,    // information_schema columns differ from the real engine
                 CHARSET_FIDELITY,         // utf8 vs utf8mb4: H2 stores anything, MySQL may truncate
                 CASE_SENSITIVITY,         // depends on the real server's config AND host filesystem
                 TYPE_COERCION             // silent widening/narrowing is engine-specific
                 -> false;
        };
    }

    /**
     * Whether the LOCAL backend can even EXECUTE this dialect's spelling of {@code construct}.
     *
     * <p>Distinct from {@link #enforces}, and the distinction matters. {@code enforces} asks "would I
     * believe the result?"; this asks "will the statement parse at all?". Both must be true for a
     * local run to mean anything, and conflating them produces the worst kind of red: a syntax error
     * from the BACKEND that reads exactly like a bug in the DIALECT.
     *
     * <p><b>The table below is measured, not assumed</b> -- probed against H2 2.2.224 in each
     * compatibility mode on 2026-08-08. Three of its entries are counter-intuitive enough to be worth
     * naming:
     *
     * <ul>
     *   <li><b>H2 in {@code MODE=PostgreSQL} cannot run {@code ON CONFLICT}.</b> Postgres's own upsert
     *       is therefore NOT locally verifiable at all -- it needs the real Postgres that
     *       {@code PostgresTestSupport} already starts in CI. This is the single clearest instance of
     *       "be honest about what H2-in-mode proves", and it was found by a red test rather than by
     *       reading documentation.</li>
     *   <li><b>H2 in {@code MODE=MySQL} CAN run {@code ON DUPLICATE KEY UPDATE}.</b> So MySQL's upsert
     *       -- the most divergent construct in the interface -- does get a real local behavioural
     *       check, which is exactly the fast signal the tiering was designed to buy.</li>
     *   <li><b>H2 in {@code MODE=MSSQLServer} correctly REJECTS {@code LIMIT ? OFFSET ?}</b> and
     *       accepts {@code OFFSET..FETCH}. The backend independently confirms that SQL Server's
     *       pagination shape is a real difference and not a stylistic one.</li>
     * </ul>
     *
     * <p>Re-probe and update this table when the H2 version changes; a stale entry turns a skip into
     * a failure or, worse, hides a construct that stopped working.
     */
    public static boolean canExecuteLocally(SqlDialect dialect, Construct construct) {
        if (isContainerBacked()) {
            return true; // the real engine runs its own SQL by definition
        }
        String name = dialect.name();
        return switch (construct) {
            // Every mode accepts standard DML, DDL and both symmetric quoting styles.
            case BASIC_DML, PAGINATION, IDENTIFIER_QUOTING, TRANSACTIONS, UNIQUE_CONSTRAINT -> true;
            // Each mode accepts its OWN auto-increment spelling and no other.
            case AUTO_INCREMENT -> true;
            // Measured: only h2's MERGE...KEY and mysql's ON DUPLICATE KEY parse locally.
            case UPSERT -> "h2".equals(name) || "mysql".equals(name);
        };
    }

    /** A SQL construct a vector needs the backend to parse. */
    public enum Construct {
        BASIC_DML,
        PAGINATION,
        IDENTIFIER_QUOTING,
        AUTO_INCREMENT,
        UNIQUE_CONSTRAINT,
        TRANSACTIONS,
        UPSERT
    }

    /** Why a vector could not RUN here, for the skip message. */
    public static String whyNotRunnable(SqlDialect dialect, Construct construct) {
        return "NOT RUN for " + dialect.name() + ": the local H2 backend cannot parse this engine's "
                + construct + " syntax (measured -- see DialectTestSupport.canExecuteLocally). This is a "
                + "backend limitation, NOT a dialect failure. It runs in CI against a real "
                + dialect.name() + " (NPDEV_DIALECT_BACKEND=container).";
    }

    /** What a backend might or might not reproduce faithfully. */
    public enum Behaviour {
        SYNTAX_SHAPE,
        IDENTIFIER_QUOTING,
        PAGINATION,
        UNIQUENESS,
        DML_TRANSACTIONALITY,
        DDL_TRANSACTIONALITY,
        NATIVE_UPSERT_SEMANTICS,
        CATALOG_INTROSPECTION,
        CHARSET_FIDELITY,
        CASE_SENSITIVITY,
        TYPE_COERCION
    }

    /** Why a vector was not verified here, for the skip message. */
    public static String whyNotVerified(SqlDialect dialect, Behaviour behaviour) {
        return "NOT VERIFIED for " + dialect.name() + ": local H2-in-compatibility-mode does not "
                + "faithfully reproduce " + behaviour + ". This vector needs a real " + dialect.name()
                + " (NPDEV_DIALECT_BACKEND=container in CI). A green local run does NOT mean this passed.";
    }
}
