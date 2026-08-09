package com.npdev.generator.dbconfig;

import java.util.List;
import java.util.Map;

/**
 * Everything the generated {@code _ops} toolbox needs to treat an engine exactly the way it treats
 * Postgres.
 *
 * <h2>Why a descriptor and not three more branches</h2>
 *
 * <p>{@code OperationalRunbookEmitter} carried five {@code if ($plan.engine -eq 'Postgres')} blocks,
 * one per user-facing operation -- create, stop, status, connect, reset. Adding MySQL and SQL Server
 * by copying them means <b>fifteen</b> blocks across five scripts, drifting apart the moment one is
 * edited. That is the trap the {@code *-postgres} adapters set for the dialect work, and
 * {@code SqlDialect}'s answer was to parameterise twelve methods rather than duplicate eight
 * modules. Same answer here: one profile per engine, one script, zero per-engine branches.
 *
 * <p>What the user saw before this existed, having been told yes by every layer above -- the config
 * schema accepts {@code mysql}, {@code npdev init --engine mysql} writes a valid definition, the
 * Manager offers it in a picker, and the dialect passes conformance against a real MySQL:
 *
 * <pre>
 *   throw "Unsupported engine 'MySQL' in resolved-db-plan.json."
 * </pre>
 *
 * <p>That is not a missing feature, it is an inconsistent promise -- and an inconsistent promise is
 * what makes software feel unprofessional, because the user cannot tell which of your yeses to
 * trust.
 *
 * <h2>What this is NOT</h2>
 *
 * <p>Not a replacement for {@code SqlDialect}. That answers "how is this SQL spelled" at RUNTIME.
 * This answers "how is this engine started and made ready" at ENVIRONMENT time. Different question,
 * different lifetime, deliberately separate types.
 */
public record DockerEngineProfile(
        DatabaseEngine engine,
        Kind kind,
        String provider,
        Integer defaultPort,
        String image,
        String driverClass,
        String jdbcUrlTemplate,
        Map<String, String> containerEnv,
        List<String> extraRunArgs,
        boolean createsDatabaseFromEnv,
        Probe readyProbe,
        EnsureDatabase ensureDatabase,
        String adminUser,
        String guiLabel,
        List<String> quirks,
        String dataVolumePath,
        String composeImage,
        String backupCommand
) {

    public enum Kind {
        /** Runs in a container this toolbox starts and stops. Must supply every field. */
        SERVER,
        /** A file or memory. No container; environment operations are directory work. */
        EMBEDDED,
        /** A java process this toolbox starts (H2Server). Container fields are null. */
        EMBEDDED_SERVER;

        static Kind parse(String raw) {
            return switch (raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "server" -> SERVER;
                case "embedded" -> EMBEDDED;
                case "embedded-server" -> EMBEDDED_SERVER;
                default -> throw new IllegalStateException(
                        "unknown engine profile kind '" + raw + "'; expected server, embedded or "
                        + "embedded-server. Refusing rather than defaulting -- a wrong kind decides "
                        + "whether the toolbox starts a container at all.");
            };
        }
    }

    /**
     * How to ask a running container whether it can actually serve.
     *
     * <p><b>{@code timeoutSeconds} is per-engine on purpose.</b> SQL Server routinely needs 30-60s
     * to start; giving it Postgres's budget reports a healthy engine as broken, and whoever hits
     * that concludes NPDev does not support SQL Server. Measured in this project already: two false
     * REDs on 2026-08-08 came from a boot timeout, not a boot failure.
     */
    public record Probe(List<String> exec, int expectExitCode, int timeoutSeconds) {
    }

    /**
     * How to guarantee the database exists once the engine is ready.
     *
     * <p><b>SQL Server has no {@code MSSQL_DATABASE} variable.</b> Its
     * {@code createsDatabaseFromEnv} is false, and skipping this step leaves the app connecting to a
     * database that was never created -- which this project has already watched {@code npdev doctor}
     * misreport as a CREDENTIALS failure, sending the reader to fix a password that was never wrong.
     */
    public record EnsureDatabase(List<String> listExec, List<String> createExec, Map<String, String> execEnv) {
    }

    /** True when this engine has a container to start -- the engines the parity gate holds to. */
    public boolean isContainerBacked() {
        return kind == Kind.SERVER;
    }

    /**
     * Fail fast on an incomplete profile, at GENERATION time.
     *
     * <p>A SERVER profile missing a probe or a database step emits scripts that appear to work and
     * leave the user with an app that cannot connect -- discovered minutes later, in a Spring stack
     * trace, by someone who did not write Spring. Refusing at generation time is the difference
     * between a build error the author sees and a runtime mystery the user sees.
     */
    public void validate() {
        if (kind != Kind.SERVER) {
            return;
        }
        require(image != null && !image.isBlank(), "image");
        require(defaultPort != null, "defaultPort");
        require(driverClass != null && !driverClass.isBlank(), "driverClass");
        require(jdbcUrlTemplate != null && !jdbcUrlTemplate.isBlank(), "jdbcUrlTemplate");
        require(containerEnv != null && !containerEnv.isEmpty(), "containerEnv");
        require(readyProbe != null && readyProbe.exec() != null && !readyProbe.exec().isEmpty(), "readyProbe");
        require(readyProbe.timeoutSeconds() > 0, "readyProbe.timeoutSeconds");
        require(ensureDatabase != null, "ensureDatabase");
        require(ensureDatabase.createExec() != null && !ensureDatabase.createExec().isEmpty(),
                "ensureDatabase.createExec");
        require(guiLabel != null && !guiLabel.isBlank(), "guiLabel");
        require(dataVolumePath != null && !dataVolumePath.isBlank(), "dataVolumePath");
        require(composeImage != null && !composeImage.isBlank(), "composeImage");
    }

    private void require(boolean condition, String field) {
        if (!condition) {
            throw new IllegalStateException(
                    "DockerEngineProfile for " + engine + " is a SERVER engine but has no '" + field
                    + "'. Every server engine must support every environment operation -- a user's "
                    + "experience must not depend on which engine they chose. If this engine "
                    + "genuinely cannot support it, give it a different Kind and refuse it at the "
                    + "point of choice; do not ship a half-working toolbox.");
        }
    }

    /** The profile as the {@code _ops} scripts read it out of {@code resolved-db-plan.json}. */
    public Map<String, Object> toPlanJson() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("kind", kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        out.put("provider", provider);
        out.put("image", image == null ? "" : image);
        out.put("guiLabel", guiLabel == null ? "" : guiLabel);
        out.put("adminUser", adminUser == null ? "" : adminUser);
        out.put("createsDatabaseFromEnv", createsDatabaseFromEnv);
        out.put("containerEnv", containerEnv == null ? Map.of() : containerEnv);
        out.put("extraRunArgs", extraRunArgs == null ? List.of() : extraRunArgs);
        Map<String, Object> probe = new java.util.LinkedHashMap<>();
        probe.put("exec", readyProbe == null ? List.of() : readyProbe.exec());
        probe.put("expectExitCode", readyProbe == null ? 0 : readyProbe.expectExitCode());
        probe.put("timeoutSeconds", readyProbe == null ? 0 : readyProbe.timeoutSeconds());
        out.put("readyProbe", probe);
        Map<String, Object> ensure = new java.util.LinkedHashMap<>();
        ensure.put("listExec", ensureDatabase == null ? List.of() : ensureDatabase.listExec());
        ensure.put("createExec", ensureDatabase == null ? List.of() : ensureDatabase.createExec());
        ensure.put("execEnv", ensureDatabase == null ? Map.of() : ensureDatabase.execEnv());
        out.put("ensureDatabase", ensure);
        // Carried to the user rather than kept here: Print-DbConnectionInfo prints them, because
        // that is the screen someone already has open when they need to know that MySQL's utf8mb4
        // is not optional or that SQL Server's 'sa' is not their app's username.
        out.put("quirks", quirks == null ? List.of() : quirks);
        out.put("dataVolumePath", dataVolumePath == null ? "" : dataVolumePath);
        return out;
    }
}
