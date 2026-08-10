package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import com.npdev.kernel.dbschema.InternalColumnDefinition;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UserDatabaseDefinitionLoader {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter INSTANCE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A backslash, from its char code -- a literal one has been mangled by every layer this file
     *  has been edited through, and this comparison must not be the thing that breaks the build. */
    private static final char BACKSLASH = (char) 92;

    /**
     * PORT-1: the ONE folder every generated app keeps its own database in, named RELATIVE to the
     * FinalApp directory.
     *
     * <p>This used to be {@code <workspace>/Build/databases/<appId>} -- an absolute path computed
     * from the AUTHORING machine's layout and then written into {@code spring.datasource.url}, which
     * Spring resolves at boot. A generated app handed to anyone else tried to open its database on a
     * drive they may not have.
     *
     * <p>Relative to WHAT is the load-bearing question, and the answer has to be the same for both
     * consumers or you get QUAL-3 again -- one database with two front doors. It is the FinalApp
     * directory: the app's working directory IS that directory in every launch path NPDev emits
     * ({@code java -jar} from {@code Run-FinalApp.ps1}/{@code Start-App.ps1}, and {@code bootRun},
     * whose working directory is the Gradle project root), and {@code _ops} lives INSIDE the app
     * (QUAL-3) so {@code $PSScriptRoot/..} names the same directory without either side naming a
     * drive.
     *
     * <p>No {@code NPDEV_DATA_ROOT}-style override is emitted, deliberately. An override that only
     * one of the two consumers honours re-creates the two-front-doors defect; one that both honour
     * is a second source of truth to keep in step. Spring's own
     * {@code --spring.datasource.url=} still works for anyone who genuinely wants to point the app
     * elsewhere, and is visibly the user's own act rather than NPDev's guess.
     *
     * <p>Twin-pair {@code app-data-root-anchor-three-seams} (token: npdev-app-data-root-anchor).
     * This DECIDES the root; {@code OperationalRunbookEmitter} resolves it back for the toolbox and
     * {@code FinalAppAssembler} spares it from the regeneration wipe. All three must agree.
     */
    private static final String DATA_ROOT_FOLDER = "data";

    public GeneratedDatabasePlan load(Path definitionPath, CompiledModel model) throws Exception {
        if (definitionPath == null) {
            throw new IllegalArgumentException("--dbDefinitionPath is required");
        }
        Path normalizedPath = definitionPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedPath)) {
            throw new IllegalArgumentException("DB definition file not found: " + normalizedPath);
        }

        JsonNode root = OBJECT_MAPPER.readTree(normalizedPath.toFile());
        JsonNode database = root.path("database");
        JsonNode lifecycle = root.path("schemaLifecycle");
        DatabaseEngine engine = DatabaseEngine.parse(text(database, "engine"));
        SchemaLifecyclePolicy policy = new SchemaLifecyclePolicy(
                SchemaLifecycleStrategy.parse(text(lifecycle, "strategy")),
                bool(lifecycle, "allowDestructiveRecreate", false),
                text(lifecycle, "destructiveRecreateConfirmation"),
                text(lifecycle, "scope"),
                DatabaseOwnership.parse(text(lifecycle, "ownership"))
        );

        UserDatabaseDefinition definition = new UserDatabaseDefinition(
                engine,
                text(database, "host"),
                integer(database, "port", 0),
                text(database, "databaseName"),
                text(database, "schemaName"),
                text(database, "jdbcUrl"),
                text(database, "h2FilePath"),
                text(database, "username"),
                rawText(database, "password"),
                bool(database, "createInternalTables", true),
                bool(database, "createBusinessTables", true),
                bool(database, "externallyProvisioned", false),
                policy
        );
        validate(definition);

        String appId = resolveAppId(normalizedPath);
        DatabaseIdentity identity = resolveIdentity(definition, appId);
        String jdbcUrl = jdbcUrl(definition, identity);
        refuseDeclarationThatDisagrees(definition, identity, jdbcUrl);
        String driver = switch (engine) {
            case POSTGRES -> "org.postgresql.Driver";
            case H2_LOCAL, H2_SERVER -> "org.h2.Driver";
            // com.mysql.cj.jdbc.Driver, not the pre-8 com.mysql.jdbc.Driver -- the old name still
            // loads with a deprecation warning and then behaves differently around time zones.
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case SQL_SERVER -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case IN_MEMORY -> "";
        };
        List<String> fingerprintInputs = fingerprintInputs(definition, model);
        String schemaFingerprint = "sha256:" + sha256(String.join("\n", fingerprintInputs));
        return new GeneratedDatabasePlan(
                appId,
                engine,
                engine.storageMode(),
                engine.jdbc(),
                definition.externallyProvisioned(),
                identity.requestedDatabaseName(),
                identity.resolvedDatabaseName(),
                identity.databaseNameSource(),
                identity.resolvedDataRoot(),
                identity.databaseInstanceId(),
                identity.containerName(),
                identity.host(),
                identity.hostPort(),
                identity.containerPort(),
                jdbcUrl,
                driver,
                definition.username(),
                definition.password(),
                identity.dbeaverHost(),
                identity.dbeaverPort(),
                identity.dbeaverDatabase(),
                definition.username(),
                definition.createInternalTables(),
                definition.createBusinessTables(),
                policy,
                schemaFingerprint,
                normalizedPath,
                fingerprintInputs
        );
    }

    /**
     * STOR-8: refuse a declared {@code jdbcUrl} or {@code h2FilePath} that DISAGREES with the
     * connection NPDev will actually make.
     *
     * <h2>Why disagreement and not the field itself</h2>
     *
     * <p>The obvious fix was to refuse both fields outright -- they read as authoritative and one of
     * them is never consulted at all. <b>Measured first: TWELVE app definitions set one of them,
     * including four official samples</b> (AuxScreen, Pigmentampa, WmsOffice, WordLab). Refusing the
     * field would have broken every one of them, and none of them is wrong -- all twelve declare
     * exactly what NPDev composes anyway.
     *
     * <p>So the hazard is narrower than "the field is ignored", and sharper: a value that CONTRADICTS
     * the real connection. Today that is silent. A user who points {@code jdbcUrl} at an existing
     * production database gets no error, no warning, and a connection to a different database -- and
     * may then write to it. That is the X0 silent-answer rule broken in the storage layer, where it
     * is least visible and most expensive.
     *
     * <p><b>What each field really does, measured rather than assumed</b> -- the filed record said
     * both were parsed and ignored, and that was only half right:
     *
     * <ul>
     *   <li>{@code h2FilePath} is read into the record and consulted by nothing. Genuinely inert.</li>
     *   <li>{@code jdbcUrl} IS consulted, for H2Server only: {@code resolveHost} and
     *       {@code resolveHostPort} parse the host and port out of it, and {@code validate} accepts
     *       it as the documented ALTERNATIVE to host/port. On every other engine it is inert.</li>
     * </ul>
     *
     * <p>Refusing rather than honouring is deliberate. Honouring an explicit URL is a FEATURE, and it
     * raises a real question -- does it bypass the identity check that stops two apps sharing a
     * database? -- which deserves its own design rather than being smuggled into a cleanup.
     */
    private static void refuseDeclarationThatDisagrees(
            UserDatabaseDefinition definition, DatabaseIdentity identity, String composedJdbcUrl) {
        String declaredUrl = definition.jdbcUrl();
        if (!declaredUrl.isBlank() && !sameConnection(declaredUrl, composedJdbcUrl)) {
            throw new IllegalArgumentException(
                    "database.jdbcUrl declares a connection NPDev will not make -- declared '"
                    + declaredUrl + "', actual '" + composedJdbcUrl + "'. NPDev composes the URL "
                    + "from engine/host/port/databaseName; an explicit jdbcUrl is not honoured, so "
                    + "leaving this in place would connect you to a DIFFERENT database than you "
                    + "asked for, silently. Either remove database.jdbcUrl, or set "
                    + "host/port/databaseName to the database you mean. (STOR-8 -- if you need an "
                    + "explicit URL, say so and it will be prioritised rather than guessed at.)");
        }
        String declaredFile = definition.h2FilePath();
        if (!declaredFile.isBlank()) {
            String expected = identity.resolvedDataRoot() + "/" + identity.resolvedDatabaseName();
            if (!samePath(declaredFile, expected)) {
                // PORT-1 changed what "actual" IS here, so the message had to change with it or the
                // check would start blaming the user for a decision NPDev made. Every definition
                // that declared the old absolute path now lands in this branch -- six real ones did
                // -- and "declared X, actual Y, remove it" without the WHY reads as a regression.
                throw new IllegalArgumentException(
                        "database.h2FilePath declares a file NPDev will not use -- declared '"
                        + declaredFile + "', actual '" + expected + "'. As of PORT-1 the H2 file is "
                        + "APP-RELATIVE: it lives at <FinalApp>/" + expected + ", so that a "
                        + "generated app can be handed to someone else and still find its own "
                        + "database. That means NPDev no longer knows an absolute path at generation "
                        + "time and an absolute h2FilePath can be neither honoured nor verified. "
                        + "Remove database.h2FilePath (it is read and then consulted by nothing, so "
                        + "removing it changes no behaviour), or set databaseName so the derived "
                        + "relative path is the one you want. To run the app against a database "
                        + "somewhere else entirely, pass --spring.datasource.url at startup. "
                        + "(STOR-8, PORT-1)");
            }
        }
    }

    /** Two JDBC URLs naming the same server and database, ignoring options and case. */
    private static boolean sameConnection(String declared, String composed) {
        return normalizeUrl(declared).equals(normalizeUrl(composed));
    }

    private static String normalizeUrl(String url) {
        // Compare the ADDRESS, not the options: an app may legitimately declare the same database
        // with a different MODE= or DB_CLOSE_ON_EXIT= than the generator emits, and failing on
        // that would be the noisy-gate failure this project refuses everywhere else.
        return url.split(";", 2)[0].trim().replace(BACKSLASH, '/').toLowerCase(Locale.ROOT);
    }

    private static boolean samePath(String declared, String expected) {
        return normalizePath(declared).equals(normalizePath(expected));
    }

    private static String normalizePath(String path) {
        String slashed = path.trim().replace(BACKSLASH, '/').toLowerCase(Locale.ROOT);
        // "./data/x" and "data/x" are the same declaration. Both spellings appear in the wild now
        // that the derived path is relative -- the JDBC URL carries the "./" form and a user copying
        // it into h2FilePath would otherwise be refused for punctuation.
        while (slashed.startsWith("./")) {
            slashed = slashed.substring(2);
        }
        while (slashed.endsWith("/")) {
            slashed = slashed.substring(0, slashed.length() - 1);
        }
        return slashed;
    }

    private static void validate(UserDatabaseDefinition definition) {
        DatabaseEngine engine = definition.engine();
        if (definition.schemaLifecycle().strategy() == SchemaLifecycleStrategy.DROP_AND_RECREATE_ON_STRUCTURE_CHANGE
                && !definition.schemaLifecycle().destructiveConfirmedFor(engine)) {
            throw new IllegalArgumentException("Destructive schema recreation requires exact confirmation and NPDev-owned scope for " + engine.externalName());
        }
        // REG-7.1: ExternallyManaged means NPDev issues NO schema DDL against this database -- a
        // recreate/destructive strategy (which exists only to issue DDL) is therefore nonsensical
        // and rejected at generation time rather than silently ignored at boot.
        if (definition.schemaLifecycle().externallyManaged()) {
            if (definition.schemaLifecycle().strategy() != SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE) {
                throw new IllegalArgumentException("schemaLifecycle.ownership=ExternallyManaged requires "
                        + "strategy=KeepExistingIfCompatible (NPDev never issues DDL against a database it does "
                        + "not own, so a recreate strategy cannot apply): got "
                        + definition.schemaLifecycle().strategy().externalName());
            }
            if (definition.schemaLifecycle().allowDestructiveRecreate()) {
                throw new IllegalArgumentException("schemaLifecycle.ownership=ExternallyManaged requires "
                        + "allowDestructiveRecreate=false (NPDev never issues DDL against a database it does "
                        + "not own).");
            }
        }
        // STOR-14: an EMBEDDED engine has no server, so there is nothing for someone else to have
        // provisioned. Refused here -- at generation time, at the point of choice -- rather than
        // accepted and then quietly ignored by five _ops scripts that have no container to skip.
        //
        // H2Server is deliberately NOT refused. Its environment is a Java process rather than a
        // container, but it is still a server someone can already be running, and every _ops
        // operation keys on the plan flag before it keys on profile.kind -- so external mode covers
        // it for free and refusing it would be an arbitrary hole.
        if (definition.externallyProvisioned()
                && (engine == DatabaseEngine.IN_MEMORY || engine == DatabaseEngine.H2_LOCAL)) {
            throw new IllegalArgumentException("database.externallyProvisioned=true is not valid for "
                    + engine.externalName() + ": it is an embedded engine, so there is no server for "
                    + "anyone to have provisioned -- the database is a file (or memory) belonging to "
                    + "this app alone. Drop the flag, or choose a server engine (Postgres, MySQL, "
                    + "SqlServer, H2Server) if you mean to connect to a database you already run. "
                    + "(STOR-14)");
        }
        if (engine == DatabaseEngine.IN_MEMORY) {
            return;
        }
        if (definition.username().isBlank()) {
            throw new IllegalArgumentException("database.username is required for " + engine.externalName());
        }
        if (engine == DatabaseEngine.POSTGRES || engine == DatabaseEngine.MYSQL
                || engine == DatabaseEngine.SQL_SERVER) {
            // Every server engine needs a reachable address. Grouped rather than repeated so a
            // fourth one cannot be added without a host/port requirement by simply not noticing.
            require(definition.host(), "database.host");
            if (definition.port() <= 0) {
                throw new IllegalArgumentException("database.port is required for " + engine.externalName());
            }
        } else if (engine == DatabaseEngine.H2_SERVER && definition.jdbcUrl().isBlank()
                && (definition.host().isBlank() || definition.port() <= 0)) {
            throw new IllegalArgumentException("database.jdbcUrl or database.host/database.port is required for H2Server");
        }
    }

    private static DatabaseIdentity resolveIdentity(UserDatabaseDefinition definition, String appId) {
        DatabaseEngine engine = definition.engine();
        if (engine == DatabaseEngine.IN_MEMORY) {
            return new DatabaseIdentity(
                    "",
                    "",
                    "none",
                    "",
                    "",
                    "",
                    "",
                    0,
                    0,
                    "",
                    0,
                    ""
            );
        }

        String requested = definition.databaseName();
        String source = requested.isBlank() ? "generated" : "explicit";
        String resolvedName = requested.isBlank() ? uniqueDatabaseName(appId) : requested;
        String instanceId = requested.isBlank() ? resolvedName : appId;
        // PORT-1. App-relative, never absolute -- see DATA_ROOT_FOLDER for why, and for what the two
        // consumers resolve it against. The generated-name subfolder is kept from the previous
        // (absolute) shape: a generated name carries a timestamp, so each generation is a distinct
        // instance and they must not land on top of each other. An EXPLICIT databaseName means the
        // user is naming one durable database, which is the case that has to survive regeneration,
        // so it stays flat at <app>/data.
        String dataRoot = requested.isBlank()
                ? DATA_ROOT_FOLDER + "/" + resolvedName
                : DATA_ROOT_FOLDER;
        String containerName = engine.usesContainer() ? "npdev-" + slug(instanceId) : "";
        String host = resolveHost(definition);
        int hostPort = resolveHostPort(definition);
        int containerPort = engine.defaultPort();
        String dbeaverHost = engine == DatabaseEngine.H2_LOCAL ? "" : host;
        int dbeaverPort = engine == DatabaseEngine.H2_LOCAL ? 0 : hostPort;
        String dbeaverDatabase = switch (engine) {
            case POSTGRES, MYSQL, SQL_SERVER -> resolvedName;
            case H2_LOCAL -> dataRoot + "/" + resolvedName;
            case H2_SERVER -> dataRoot + "/" + resolvedName;
            case IN_MEMORY -> "";
        };
        return new DatabaseIdentity(
                requested,
                resolvedName,
                source,
                dataRoot,
                instanceId,
                containerName,
                host,
                hostPort,
                containerPort,
                dbeaverHost,
                dbeaverPort,
                dbeaverDatabase
        );
    }

    private static String jdbcUrl(UserDatabaseDefinition definition, DatabaseIdentity identity) {
        return switch (definition.engine()) {
            case IN_MEMORY -> "";
            case POSTGRES -> "jdbc:postgresql://" + identity.host() + ":" + identity.hostPort()
                    + "/" + identity.resolvedDatabaseName();
            // REG-57: H2's MVStore defaults to a 500ms WRITE_DELAY, buffering committed writes in
            // memory before flushing to disk -- a hard kill inside that window silently loses
            // however many commits landed since the last flush, even though the JDBC call already
            // returned and the caller was already told the write succeeded (proven live: a durable
            // flow's WAITING_EVENT checkpoint, plus several prior step checkpoints, vanished on a
            // kill within ~1s of the HTTP response; absent with a 5s buffer). WRITE_DELAY=0 forces a
            // flush on every commit, trading write throughput for the durability this engine exists
            // to provide. Postgres is unaffected -- COMMIT is synchronous to WAL there, no analogous
            // buffering parameter exists or is needed.
            // PORT-1: "./" + an app-relative root, so Spring resolves it at boot against the app's
            // own working directory instead of against a drive letter from the machine that
            // generated it. H2 resolves a relative file: path against the JVM's working directory.
            case H2_LOCAL -> "jdbc:h2:file:./" + identity.resolvedDataRoot() + "/" + identity.resolvedDatabaseName()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            // H2Server resolves the path SERVER-side, so the relative path is relative to the H2
            // process's baseDir -- which Create-Environment sets to the FinalApp directory, the same
            // anchor the client uses. Both halves therefore name <app>/data/<db>, from either end.
            case H2_SERVER -> "jdbc:h2:tcp://" + identity.host() + ":" + identity.hostPort()
                    + "/./" + identity.resolvedDataRoot() + "/" + identity.resolvedDatabaseName()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";
            // characterEncoding=UTF-8 and connectionCollation=utf8mb4 are NOT cosmetic. MySQL's
            // legacy "utf8" is a three-byte encoding that cannot represent anything outside the
            // BMP, so on a default connection an emoji or some CJK text is truncated or replaced
            // SILENTLY -- the insert succeeds and the data is already wrong. Conformance J2 exists
            // for exactly this. serverTimezone=UTC keeps DATETIME values meaning what they said.
            case MYSQL -> "jdbc:mysql://" + identity.host() + ":" + identity.hostPort()
                    + "/" + identity.resolvedDatabaseName()
                    + "?characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci"
                    + "&serverTimezone=UTC&rewriteBatchedStatements=true";
            // encrypt=true is the driver default from mssql-jdbc 10 onward; stating both it and
            // trustServerCertificate keeps a local/dev instance with a self-signed cert working
            // without silently turning encryption off in production too.
            case SQL_SERVER -> "jdbc:sqlserver://" + identity.host() + ":" + identity.hostPort()
                    + ";databaseName=" + identity.resolvedDatabaseName()
                    + ";encrypt=true;trustServerCertificate=true";
        };
    }

    private static String resolveHost(UserDatabaseDefinition definition) {
        if (!definition.host().isBlank()) {
            return definition.host();
        }
        if (definition.engine() == DatabaseEngine.H2_SERVER && !definition.jdbcUrl().isBlank()) {
            Matcher matcher = Pattern.compile("^jdbc:h2:tcp://([^/:;]+)(?::(\\d+))?/.*").matcher(definition.jdbcUrl());
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return "localhost";
    }

    private static int resolveHostPort(UserDatabaseDefinition definition) {
        if (definition.port() > 0) {
            return definition.port();
        }
        if (definition.engine() == DatabaseEngine.H2_SERVER && !definition.jdbcUrl().isBlank()) {
            Matcher matcher = Pattern.compile("^jdbc:h2:tcp://[^/:;]+:(\\d+)/.*").matcher(definition.jdbcUrl());
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return definition.engine().defaultPort();
    }

    private static String resolveAppId(Path definitionPath) {
        Path definitionDir = definitionPath.getParent();
        Path appDir = definitionDir == null ? null : definitionDir.getParent();
        Path manifest = definitionDir == null ? null : definitionDir.resolve("manifest.json");
        if (manifest != null && Files.isRegularFile(manifest)) {
            try {
                String id = text(OBJECT_MAPPER.readTree(manifest.toFile()), "id");
                if (!id.isBlank()) {
                    return slug(id);
                }
            } catch (Exception ignored) {
            }
        }
        // QUAL-3, second half -- solved by the manifest branch ABOVE, deliberately, not by changing
        // this fallback.
        //
        // The fallback walks two directory levels up, which is right for `<App>/definition/...` and
        // `<App>/Input/...` and wrong for `npdev init`'s layout, where the definition sits directly
        // in the app directory so two levels up is the PARENT FOLDER shared by every app in it.
        // That is what made two apps resolve to one appId, one containerName and one data root.
        //
        // Keying on the directory NAME instead was tried and MEASURED to be worse: 25 corpus
        // definitions live in a directory called `Input` with no manifest, and treating "not named
        // `definition`" as "this directory is the app" would have collapsed all 25 onto
        // `appId=Input` -- a wider collision than the one being fixed, in the corpus rather than in
        // a user's folder. Path shape cannot tell an app directory from a wrapper directory, so it
        // is not asked to: `npdev init` now WRITES a manifest.json naming the app, and the branch
        // above reads it. Identity is declared, not inferred.
        if (appDir != null && appDir.getFileName() != null) {
            return slug(appDir.getFileName().toString());
        }
        return "npdev-app";
    }

    // resolveWorkspaceRoot() was deleted here by PORT-1, not moved. It walked up looking for a
    // directory named "AppGen", then for one holding BOTH "Build" and a directory literally named
    // "NPDev_General" (the folder-NAME predicate REG-144 removed from eleven other sites), and
    // finally fell back to the literal Path.of("D:/WorkSpace/NPDev"). Its only caller was the data
    // root, which is now app-relative -- so the whole question "which workspace generated this?" no
    // longer has to be answered to know where an app keeps its database, which is the right shape:
    // the answer was never about the authoring machine in the first place.

    private static String uniqueDatabaseName(String appId) {
        byte[] bytes = new byte[2];
        RANDOM.nextBytes(bytes);
        return slug(appId).replace('-', '_') + "_" + INSTANCE_FORMAT.format(LocalDateTime.now()) + "_"
                + HexFormat.of().formatHex(bytes);
    }

    /**
     * LNCH-1 P6 (task 6.1): the exact production fingerprint computation, extracted so
     * {@code com.npdev.generator.schemaevolution.MigrationPlanEmitter} can compute the "previous"
     * model's fingerprint (needed for {@code fromFingerprint}) via the SAME algorithm {@link #load}
     * uses for the "current" model's fingerprint -- never a hand-re-derived approximation. Callers
     * needing the CURRENT model's fingerprint should prefer the value {@link #load} already
     * computed ({@link GeneratedDatabasePlan#schemaFingerprint()}) over calling this a second time
     * with equivalent inputs; this method exists for computing the fingerprint of a DIFFERENT
     * (typically: previous) compiled model against the same database definition.
     */
    public static String computeSchemaFingerprint(UserDatabaseDefinition definition, CompiledModel model) {
        return "sha256:" + sha256(String.join("\n", fingerprintInputs(definition, model)));
    }

    private static List<String> fingerprintInputs(UserDatabaseDefinition definition, CompiledModel model) {
        List<String> inputs = new ArrayList<>();
        inputs.add("engine=" + definition.engine().externalName());
        inputs.add("storageMode=" + definition.engine().storageMode());
        inputs.add("lifecycle=" + definition.schemaLifecycle().strategy().externalName());
        inputs.add("scope=" + definition.schemaLifecycle().scope());
        if (definition.createInternalTables()) {
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                inputs.add("internal.table=" + table.name());
                for (InternalColumnDefinition column : table.columns()) {
                    inputs.add("internal.column=" + table.name() + "." + column.name() + ":" + column.type().name()
                            + ":required=" + column.required() + ":default=" + column.defaultExpression());
                }
            }
        }
        if (definition.createBusinessTables() && model != null) {
            for (CompiledConcept concept : model.getConcepts()) {
                inputs.add("business.table=" + safeTable(concept));
                for (CompiledField field : concept.getFields()) {
                    inputs.add("business.column=" + safeTable(concept) + "." + SqlIdentifierSupport.columnName(field) + ":"
                            + mapType(field) + ":required=" + field.isRequired() + ":unique=" + field.isUnique());
                }
            }
        }
        return List.copyOf(inputs);
    }

    static String mapType(CompiledField field) {
        return SqlTypeSupport.sqlType(field);
    }

    static String safeTable(CompiledConcept concept) {
        return SqlIdentifierSupport.tableName(concept);
    }

    static String toSnake(String value) {
        return SqlIdentifierSupport.toSnake(value);
    }

    private static String slug(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "npdev-app" : normalized;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute schema fingerprint", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = rawText(node, field);
        return value == null ? "" : value.trim();
    }

    private static String rawText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }

    private static int integer(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private static void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private record DatabaseIdentity(
            String requestedDatabaseName,
            String resolvedDatabaseName,
            String databaseNameSource,
            String resolvedDataRoot,
            String databaseInstanceId,
            String containerName,
            String host,
            int hostPort,
            int containerPort,
            String dbeaverHost,
            int dbeaverPort,
            String dbeaverDatabase
    ) {
    }
}
