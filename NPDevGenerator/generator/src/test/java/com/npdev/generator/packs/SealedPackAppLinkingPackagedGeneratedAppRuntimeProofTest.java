package com.npdev.generator.packs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.assembly.FinalAppAssembler;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * R8.10 / BUILD-2 (REST-layer follow-on, this session): the item's own literal done-when, live --
 * "An app generated with the identity pack as a sealed jar (no identity sources in its tree) boots
 * and serves identity CRUD". Seals the REAL {@code NPDevContract/packs/identity/pack.json} to a jar
 * ({@link SealedPackJarBuilder}), generates a minimal app (zero concepts of its own) that links it
 * via {@link GeneratorFacade}'s {@code linkedSealedPacks} parameter, assembles a real FinalApp that
 * links the jar (not identity's own generated sources) via {@link FinalAppAssembler}'s {@code
 * sealedPackLinks}, boots the packaged app for real, and drives identity::User CRUD entirely over
 * HTTP -- create, read, list, update, delete -- through the generated controller whose entity import
 * resolves to the sealed jar's own {@code com.npdev.pack.identity.v1.User}, not the app's default
 * {@code com.npdev.generated.entities} package (which never contains an IdentityUser class here,
 * since the entity is intentionally NOT generated for a linked concept).
 *
 * <p>Same harness pattern as {@code HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest}
 * (generate/assemble/build/boot a real FinalApp, minutes each) -- see that class's own comments for
 * why this duplicates rather than shares the Gradle-subprocess/health-wait/cleanup plumbing.
 */
@Tag("packaged-proof")
final class SealedPackAppLinkingPackagedGeneratedAppRuntimeProofTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    private static final Path OUTSIDE_ROOT = WORKSPACE_ROOT.resolveSibling(WORKSPACE_ROOT.getFileName() + "__OutsideRepo");
    private static final Path PROOF_ROOT = OUTSIDE_ROOT.resolve("build2-sealed-pack-app-linking-runtime");
    private static final String API_KEY = "api-dev";
    private static final String TENANT = "dev";
    // Matches NpdevSettings.SECURITY_SUPER_USER_ROLE's own default ("ADMIN") -- this test never
    // overrides that setting, so the generated permission manifest's admin-only grants for
    // identity::User (a built-in-pack concept) are keyed to this exact role.
    private static final String ADMIN_ROLE = "ADMIN";

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void linkedIdentityPackBootsAndServesUserCrudOverHttp() throws Exception {
        String runId = "build2-link-" + System.currentTimeMillis();
        Path runRoot = PROOF_ROOT.resolve(runId);
        try {
            runProof(runId, runRoot);
        } finally {
            deleteRecursively(runRoot);
        }
    }

    private void runProof(String runId, Path runRoot) throws Exception {
        Path identityPackFile = WORKSPACE_ROOT.resolve("NPDevContract/packs/identity/pack.json");
        assertTrue(Files.isRegularFile(identityPackFile), "expected " + identityPackFile + " to exist");

        Path generatedRoot = runRoot.resolve("generated-artifact");
        Path schemaRoot = generatedRoot.resolve("src/main/resources/db/schema-realization");
        Path finalAppRoot = runRoot.resolve("generated-app");
        Path evidenceRoot = runRoot.resolve("proof-output");
        Path sealedJarsRoot = runRoot.resolve("sealed-jars");
        Files.createDirectories(evidenceRoot);
        Files.createDirectories(sealedJarsRoot);

        // 1. Seal the REAL identity pack into a real jar -- the same tested API
        // SealedPackJarBuilderTest proves byte-identical across independent builds.
        Path sealedJar = sealedJarsRoot.resolve("identity-1.0.0.jar");
        SealedPackJarBuilder.JarResult sealed = new SealedPackJarBuilder().sealToJar(identityPackFile, sealedJar);
        LinkedSealedPack identityLink = new LinkedSealedPack("identity", sealed.manifest());

        // 2. Compose a minimal app (zero concepts of its own) with identity's concepts, exactly as
        // BuiltinPackComposerTest does for a normal (non-sealed) app -- the linking decision is made
        // at GENERATION time (step 3 below), not composition time.
        Path modelSource = writeLinkProofModel(runRoot);
        ModelAst ast = new JsonModelParser().parse(modelSource);
        CompiledModel app = new ModelCompiler().compile(ast);
        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> identityConcepts =
                composer.loadPackConcepts(identityPackFile, "identity");
        CompiledModel merged = composer.merge(app, identityConcepts);

        // 3. Generate: identity's ENTITY is excluded (it lives in the sealed jar); its
        // DTO/service/controller ARE generated, resolved against the sealed jar's own package.
        GeneratedDatabasePlan plan = h2JdbcPlan(runRoot, modelSource, runId);
        new GeneratorFacade(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(generatedRoot, new RegenerationPolicy()))
                .generate(merged, generatedRoot, schemaRoot, null, plan, List.of(), null, List.of(identityLink));

        assertTrue(
                Files.notExists(generatedRoot.resolve("src/main/java/com/npdev/generated/entities/IdentityUser.java")),
                "identity is linked -- its entity must not be generated into this app's tree");
        assertTrue(
                Files.exists(generatedRoot.resolve("src/main/java/com/npdev/generated/controllers/IdentityUserController.java")),
                "identity is linked -- its controller must still be generated so it is reachable");

        // 4. Assemble: link the sealed jar (not identity's own sources) into libs/sealed-packs/.
        FinalAppAssembler.AssemblyResult assemblyResult = new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        WORKSPACE_ROOT.resolve("NPDevRuntimeHost"),
                        generatedRoot,
                        finalAppRoot,
                        schemaRoot,
                        "npdev-generated",
                        "npdev-meta",
                        true,
                        17,
                        null,
                        List.of(new FinalAppAssembler.SealedPackLink("identity", sealedJar))
                )
        );
        Files.writeString(evidenceRoot.resolve("packaged-app-generation-output.txt"),
                "Final app root: " + assemblyResult.finalAppRoot() + System.lineSeparator()
                        + "Generated mount: " + assemblyResult.generatedMount() + System.lineSeparator()
                        + "Sealed pack package: " + sealed.manifest().packageName() + System.lineSeparator(),
                StandardCharsets.UTF_8);
        assertTrue(Files.isRegularFile(finalAppRoot.resolve("libs/sealed-packs/identity-1.0.0.jar")),
                "the sealed identity jar must be linked into the assembled app, not regenerated");

        Path runtimeHostLibs = ensureRuntimeHostLibs(evidenceRoot);

        // 5. Build the real FinalApp -- if the REST-layer emitters resolved the wrong package/class
        // name for the linked entity, this step is where it would fail (javac: cannot find symbol).
        CommandResult bootJar = runCommand(
                List.of(gradlewPath(finalAppRoot).toString(), "--no-daemon", "bootJar",
                        "-PnpdevRuntimeHostLibsDir=" + runtimeHostLibs),
                finalAppRoot,
                Map.of("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString()),
                Duration.ofMinutes(6)
        );
        Files.writeString(evidenceRoot.resolve("packaged-app-build-output.txt"), bootJar.output(), StandardCharsets.UTF_8);
        assertEquals(0, bootJar.exitCode(), bootJar.output());

        Path jar = findBootJar(finalAppRoot);
        int port = freePort();
        String jdbcUrl = "jdbc:h2:mem:" + runId.replace("-", "_")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        Process appProcess = startPackagedApp(finalAppRoot, jar, port, jdbcUrl, runtimeHostLibs, evidenceRoot);
        try {
            waitForHealth(port, evidenceRoot);
            HttpClient client = HttpClient.newHttpClient();

            // --- 6. Real HTTP CRUD against the LINKED concept -----------------------------------
            Map<String, Object> created = createUser(client, port, "alice", "Alice Example");
            String userId = String.valueOf(created.get("id"));
            assertTrue(userId != null && !userId.isBlank() && !"null".equals(userId),
                    "create response should carry a generated id: " + created);

            Map<String, Object> fetched = getUser(client, port, userId);
            assertEquals("alice", fetched.get("username"), "GET should return the created user");

            List<Map<String, Object>> listed = listUsers(client, port);
            assertTrue(listed.stream().anyMatch(u -> userId.equals(String.valueOf(u.get("id")))),
                    "LIST should include the created user");

            Map<String, Object> updated = updateUser(client, port, userId, "Alice Updated");
            assertEquals("Alice Updated", updated.get("displayName"), "UPDATE should persist the new display name");

            int deleteStatus = deleteUser(client, port, userId);
            assertEquals(204, deleteStatus, "DELETE should succeed");

            int afterDeleteStatus = getUserStatus(client, port, userId);
            assertEquals(404, afterDeleteStatus, "the deleted user should no longer be found");

            Files.writeString(evidenceRoot.resolve("build2-link-proof-output.txt"),
                    "Sealed pack package: " + sealed.manifest().packageName() + System.lineSeparator()
                            + "Created: " + created + System.lineSeparator()
                            + "Fetched: " + fetched + System.lineSeparator()
                            + "List size: " + listed.size() + System.lineSeparator()
                            + "Updated: " + updated + System.lineSeparator()
                            + "Delete status: " + deleteStatus + System.lineSeparator()
                            + "Get-after-delete status: " + afterDeleteStatus + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } finally {
            appProcess.destroy();
            if (!appProcess.waitFor(15, TimeUnit.SECONDS)) {
                appProcess.destroyForcibly();
                appProcess.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    // --- identity::User REST helpers (route = "identity_users", see SqlIdentifierSupport.aliasPreservingTableName) ---

    private static Map<String, Object> createUser(HttpClient client, int port, String username, String displayName) throws Exception {
        Map<String, Object> body = Map.of("username", username, "displayName", displayName, "active", true);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users"))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for create: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static Map<String, Object> getUser(HttpClient client, int port, String id) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for get: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static int getUserStatus(HttpClient client, int port, String id) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listUsers(HttpClient client, int port) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users"))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for list: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), new TypeReference<List<Map<String, Object>>>() {});
    }

    private static Map<String, Object> updateUser(HttpClient client, int port, String id, String newDisplayName) throws Exception {
        Map<String, Object> body = Map.of("username", "alice", "displayName", newDisplayName, "active", true);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for update: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static int deleteUser(HttpClient client, int port, String id) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/identity_users/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode();
    }

    // --- fixture / plumbing (same harness pattern as the sibling packaged-proof tests) ---

    private static Path writeLinkProofModel(Path runRoot) throws IOException {
        Path modelRoot = runRoot.resolve("model");
        Files.createDirectories(modelRoot);
        Path modelSource = modelRoot.resolve("model.json");
        // model.schema.json requires concepts to be non-empty (minItems: 1) -- this app's own
        // concept is otherwise irrelevant to the proof, which is entirely about the LINKED
        // identity::User concept composed in separately (see runProof).
        Files.writeString(modelSource, """
                {
                  "namespace": "build2.linkproof",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "LinkProofMarker",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        return modelSource;
    }

    private static GeneratedDatabasePlan h2JdbcPlan(Path runRoot, Path modelSource, String runId) {
        String dbName = runId.replace("-", "_");
        return new GeneratedDatabasePlan(
                "build2-link-proof",
                DatabaseEngine.H2_LOCAL,
                "jdbc",
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                dbName,
                dbName,
                "build2-link-proof",
                runRoot.resolve("runtime-data").toString(),
                "build2-link-proof-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "sha256:build2-link-proof",
                modelSource,
                List.of("build2-link-proof")
        );
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup; a locked file (e.g. a not-yet-released jar handle) should not fail the test
                }
            });
        }
    }

    /** Same cross-process lock discipline the sibling packaged-proof tests use -- see {@code
     *  HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest#withKernelBuildLock}'s own
     *  javadoc for why a JVM-local lock is not enough. */
    private static <T> T withKernelBuildLock(java.util.concurrent.Callable<T> action) throws Exception {
        Path lockFile = WORKSPACE_ROOT.resolve("Build").resolve("npdev-kernel-adapter-build.lock");
        Files.createDirectories(lockFile.getParent());
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            return action.call();
        }
    }

    private static Path ensureRuntimeHostLibs(Path evidenceRoot) throws Exception {
        return withKernelBuildLock(() -> doEnsureRuntimeHostLibs(evidenceRoot));
    }

    private static Path doEnsureRuntimeHostLibs(Path evidenceRoot) throws Exception {
        Path runtimeHostLibs = OUTSIDE_ROOT.resolve("runtimehost-libs").toAbsolutePath().normalize();
        Path manifest = runtimeHostLibs.resolve("runtimehost-libs-manifest.json");
        CommandResult adapterJars = runCommand(
                List.of(
                        gradlewPath(WORKSPACE_ROOT.resolve("NPDevKernel")).toString(),
                        ":adapters:auth-context-jwt:jar",
                        ":adapters:authz-default:jar",
                        ":adapters:bulkhead-inproc:jar",
                        ":adapters:bulkhead-postgres:jar",
                        ":adapters:circuit-inproc:jar",
                        ":adapters:circuit-postgres:jar",
                        ":adapters:document-render-inproc:jar",
                        ":adapters:document-render-stub:jar",
                        ":adapters:runtime-support:jar",
                        ":adapters:external-ai-http:jar",
                        ":adapters:external-ai-inproc:jar",
                        ":adapters:external-ai-pack-core:jar",
                        ":adapters:file-store-inproc:jar",
                        ":adapters:file-store-objectstore:jar",
                        ":adapters:flow-compiled:jar",
                        ":adapters:json-jackson:jar",
                        ":adapters:metrics-micrometer:jar",
                        ":adapters:notification-inproc:jar",
                        ":adapters:mail-inproc:jar",
                        ":adapters:mail-smtp:jar",
                        ":adapters:messaging-http:jar",
                        ":adapters:messaging-inproc:jar",
                        ":adapters:persistence-inproc:jar",
                        ":adapters:persistence-postgres:jar",
                        ":adapters:resume-bootstrap-spring:jar",
                        ":adapters:runtime-validation:jar",
                        ":adapters:schema-validator-default:jar",
                        ":adapters:tracing-redaction-default:jar",
                        ":adapters:webhook-http:jar",
                        ":adapters:webhook-inproc:jar",
                        "--no-daemon",
                        "--console=plain"
                ),
                WORKSPACE_ROOT.resolve("NPDevKernel"),
                Map.of(),
                Duration.ofMinutes(5)
        );
        assertEquals(0, adapterJars.exitCode(), adapterJars.output());

        Path report = evidenceRoot.resolve("runtimehost-libs-sync-report.json");
        CommandResult result = runCommand(
                List.of(
                        "pwsh",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        WORKSPACE_ROOT.resolve("scripts/runtimehost/sync-runtimehost-libs.ps1").toString(),
                        "-WorkspaceRoot",
                        WORKSPACE_ROOT.toString(),
                        "-RuntimeHostLibs",
                        runtimeHostLibs.toString(),
                        "-ReportPath",
                        report.toString()
                ),
                WORKSPACE_ROOT,
                Map.of(),
                Duration.ofMinutes(4)
        );
        Files.writeString(evidenceRoot.resolve("runtimehost-libs-output.txt"),
                "TARGETED_ADAPTER_JARS_BUILD=" + System.lineSeparator()
                        + adapterJars.output()
                        + System.lineSeparator()
                        + "RUNTIMEHOST_LIBS_SYNC=" + System.lineSeparator()
                        + result.output(),
                StandardCharsets.UTF_8);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(manifest), "RuntimeHost libs manifest must exist after sync: " + manifest);

        Path runtimeHostCoreRoot = WORKSPACE_ROOT.resolve("NPDevRuntimeHost/runtimehost-core");
        CommandResult runtimeHostCoreJar = runCommand(
                List.of(
                        gradlewPath(runtimeHostCoreRoot).toString(),
                        "jar",
                        "sourcesJar",
                        "-PnpdevRuntimeHostLibsDir=" + runtimeHostLibs,
                        "--no-daemon",
                        "--console=plain"
                ),
                runtimeHostCoreRoot,
                Map.of(),
                Duration.ofMinutes(4)
        );
        assertEquals(0, runtimeHostCoreJar.exitCode(), runtimeHostCoreJar.output());

        Path secondReport = evidenceRoot.resolve("runtimehost-libs-sync-report-2.json");
        CommandResult secondSync = runCommand(
                List.of(
                        "pwsh",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        WORKSPACE_ROOT.resolve("scripts/runtimehost/sync-runtimehost-libs.ps1").toString(),
                        "-WorkspaceRoot",
                        WORKSPACE_ROOT.toString(),
                        "-RuntimeHostLibs",
                        runtimeHostLibs.toString(),
                        "-ReportPath",
                        secondReport.toString()
                ),
                WORKSPACE_ROOT,
                Map.of(),
                Duration.ofMinutes(4)
        );
        assertEquals(0, secondSync.exitCode(), secondSync.output());
        return runtimeHostLibs;
    }

    private static Process startPackagedApp(
            Path finalAppRoot,
            Path jar,
            int port,
            String jdbcUrl,
            Path runtimeHostLibs,
            Path evidenceRoot
    ) throws IOException {
        Path bootLog = evidenceRoot.resolve("packaged-app-boot-output.txt");
        ProcessBuilder builder = new ProcessBuilder(
                "java",
                "-jar",
                jar.toString(),
                "--server.port=" + port,
                "--spring.profiles.active=dev,step0,trial",
                "--npdev.storage.mode=jdbc",
                "--npdev.database.engine=H2",
                // identity::User is a BUILT-IN PACK concept -- RuntimeApiEmitter's own generated
                // permission manifest reserves create/update/delete/read/list on it to the
                // configured super-user role (security.superUserRole, default "ADMIN") ONLY, never
                // to the generic "user" role (see RuntimeApiEmitter#isAdminConcept). Auth must
                // therefore stay ENABLED here (unlike the sibling packaged-proof tests, whose own
                // concepts are ordinary app concepts open to "user" too) with a real api-key ->
                // ADMIN mapping, or every request 403s before this proof ever reaches the linked
                // entity/controller code this test exists to exercise.
                "--npdev.auth.enabled=true",
                "--npdev.auth.api-keys=" + API_KEY + "=" + TENANT + ":developer:" + ADMIN_ROLE,
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/schema-realization"
        );
        builder.directory(finalAppRoot.toFile());
        builder.environment().put("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread logThread = new Thread(() -> copyProcessOutput(process, bootLog), "build2-link-app-log");
        logThread.setDaemon(true);
        logThread.start();
        return process;
    }

    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(8);

    private static void waitForHealth(int port, Path evidenceRoot) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://localhost:" + port + "/actuator/health");
        Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Files.writeString(evidenceRoot.resolve("packaged-app-readiness-output.txt"),
                            "READY " + response.statusCode() + " " + response.body(),
                            StandardCharsets.UTF_8);
                    return;
                }
            } catch (Exception exception) {
                last = exception;
            }
            Thread.sleep(1000L);
        }
        throw new IllegalStateException(
                "Packaged app did not become healthy on port " + port + " within " + HEALTH_TIMEOUT
                        + " (see " + evidenceRoot.resolve("packaged-app-boot-output.txt") + "). If the log shows a"
                        + " normal startup that simply ran past the deadline, this is fork-contention, not a"
                        + " regression -- re-run this test alone to confirm.", last);
    }

    private static Path gradlewPath(Path root) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path gradlew = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!windows) {
            if (!Files.exists(gradlew)) {
                throw new IOException("gradlewPath: " + gradlew + " does not exist -- final app assembly may not have completed yet");
            }
            Files.setPosixFilePermissions(gradlew, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return gradlew;
    }

    private static CommandResult runCommand(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Duration timeout
    ) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException exception) {
                output.append("Failed reading command output: ").append(exception).append(System.lineSeparator());
            }
        });
        reader.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(5000L);
        return new CommandResult(finished ? process.exitValue() : -1, output.toString());
    }

    private static Path findBootJar(Path finalAppRoot) throws IOException {
        Path libs = finalAppRoot.resolve("build/libs");
        try (Stream<Path> stream = Files.list(libs)) {
            Optional<Path> jar = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("plain"))
                    .findFirst();
            assertTrue(jar.isPresent(), "Expected bootJar under " + libs);
            return jar.get();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("localhost", 0));
            return socket.getLocalPort();
        }
    }

    private static void copyProcessOutput(Process process, Path destination) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                Files.write(destination, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("NPDevRuntimeHost"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevContract"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to resolve NPDev_General workspace root from " + current);
    }

    private record CommandResult(int exitCode, String output) {
    }
}
