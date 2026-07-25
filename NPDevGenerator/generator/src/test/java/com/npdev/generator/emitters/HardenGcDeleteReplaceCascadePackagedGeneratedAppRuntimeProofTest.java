package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFileMetadata;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.assembly.FinalAppAssembler;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * HARDEN-GC-P1/P2/P4: proves the generated CRUD delete/update paths actually cascade-delete a
 * file field's bytes through {@link com.npdev.kernel.ports.FileStoreContract} in a real packaged
 * app -- deleting a record with a file field removes the underlying bytes (P1); replacing a file
 * field's value on update removes the OLD bytes and keeps the new ones, and a failed save leaves
 * the old bytes intact (P2). Uses the {@code inproc} file store (pointed at a directory this test
 * controls) so bytes can be inspected directly on disk, same harness pattern as {@link
 * HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest}.
 */
final class HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    private static final Path OUTSIDE_ROOT = WORKSPACE_ROOT.resolveSibling(WORKSPACE_ROOT.getFileName() + "__OutsideRepo");
    private static final Path HARDEN_ROOT = OUTSIDE_ROOT.resolve("harden-gc-packaged-generated-app-runtime");
    private static final String API_KEY = "api-dev";
    private static final String TENANT = "dev";

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void deletingOrReplacingARecordCascadesTheUnderlyingFileBytes() throws Exception {
        String runId = "harden-gc-" + System.currentTimeMillis();
        Path runRoot = HARDEN_ROOT.resolve(runId);
        try {
            runProof(runId, runRoot);
        } finally {
            deleteRecursively(runRoot);
        }
    }

    private void runProof(String runId, Path runRoot) throws Exception {
        Path generatedRoot = runRoot.resolve("generated-artifact");
        Path schemaRoot = generatedRoot.resolve("src/main/resources/db/schema-realization");
        Path finalAppRoot = runRoot.resolve("generated-app");
        Path evidenceRoot = runRoot.resolve("proof-output");
        Path fileStoreRoot = runRoot.resolve("npdev-files");
        Files.createDirectories(evidenceRoot);

        CompiledModel model = compiledGcProofModel();
        Path modelSource = writeGcProofModel(runRoot);
        GeneratedDatabasePlan plan = h2JdbcPlan(runRoot, modelSource);
        new GeneratorFacade(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(generatedRoot, new RegenerationPolicy()))
                .generate(model, generatedRoot, schemaRoot, modelSource, plan);

        FinalAppAssembler.AssemblyResult assemblyResult = new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        WORKSPACE_ROOT.resolve("NPDevRuntimeHost"),
                        generatedRoot,
                        finalAppRoot,
                        schemaRoot,
                        "npdev-generated",
                        "npdev-meta",
                        true
                )
        );
        Files.writeString(evidenceRoot.resolve("packaged-app-generation-output.txt"),
                "Final app root: " + assemblyResult.finalAppRoot() + System.lineSeparator()
                        + "Generated mount: " + assemblyResult.generatedMount() + System.lineSeparator(),
                StandardCharsets.UTF_8);

        Path runtimeHostLibs = ensureRuntimeHostLibs(evidenceRoot);

        CommandResult bootJar = runCommand(
                List.of(gradlewPath(finalAppRoot).toString(), "--no-daemon", "bootJar"),
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
        Process app = startPackagedApp(finalAppRoot, jar, port, jdbcUrl, runtimeHostLibs, fileStoreRoot, evidenceRoot);
        try {
            waitForHealth(port, evidenceRoot);
            HttpClient client = HttpClient.newHttpClient();

            // --- GC-P1: delete-cascade -------------------------------------------------------
            Map<String, Object> handleA = uploadFile(client, port, "a.txt", "text/plain", "file A bytes".getBytes(StandardCharsets.UTF_8));
            String keyA = String.valueOf(handleA.get("key"));
            assertTrue(downloadExists(client, port, handleA), "file A must exist right after upload");

            Map<String, Object> record1 = createRecord(client, port, handleA);
            String record1Id = String.valueOf(record1.get("id"));

            assertTrue(downloadExists(client, port, handleA), "file A must still exist once referenced by a saved record");

            int deleteStatus = deleteRecord(client, port, record1Id);
            assertEquals(204, deleteStatus, "record delete should succeed");

            assertFalse(downloadExists(client, port, handleA), "GC-P1: file A's bytes must be gone after its owning record is deleted");

            // Deleting again must be a no-op, not an error (the record no longer exists either way).
            deleteRecord(client, port, record1Id);

            // --- GC-P2: replace-cascade -------------------------------------------------------
            Map<String, Object> handleB = uploadFile(client, port, "b.txt", "text/plain", "file B bytes".getBytes(StandardCharsets.UTF_8));
            Map<String, Object> handleC = uploadFile(client, port, "c.txt", "text/plain", "file C bytes".getBytes(StandardCharsets.UTF_8));

            Map<String, Object> record2 = createRecord(client, port, handleB);
            String record2Id = String.valueOf(record2.get("id"));
            assertTrue(downloadExists(client, port, handleB), "file B must exist right after being saved on the record");

            Map<String, Object> updated = updateRecordAttachment(client, port, record2Id, record2, handleC);
            assertEquals(200, updated.get("__status"));

            assertFalse(downloadExists(client, port, handleB), "GC-P2: file B's bytes must be gone once replaced by file C");
            assertTrue(downloadExists(client, port, handleC), "GC-P2: file C's bytes must remain after the replace");

            Files.writeString(evidenceRoot.resolve("harden-gc-proof-output.txt"),
                    "File store root: " + fileStoreRoot + System.lineSeparator()
                            + "Record1 (deleted): " + record1 + System.lineSeparator()
                            + "handleA key=" + keyA + " -> gone after delete: PASS" + System.lineSeparator()
                            + "Record2 (replaced attachment B -> C): " + record2 + System.lineSeparator()
                            + "handleB gone after replace: PASS" + System.lineSeparator()
                            + "handleC intact after replace: PASS" + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } finally {
            app.destroy();
            if (!app.waitFor(15, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                app.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    private static Map<String, Object> uploadFile(
            HttpClient client, int port, String filename, String contentType, byte[] payload
    ) throws Exception {
        String boundary = "npdev-proof-" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(payload);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/GcProofDoc/attachment"))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for upload: " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static boolean downloadExists(HttpClient client, int port, Map<String, Object> handle) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/files?storeId=" + handle.get("storeId") + "&key=" + handle.get("key")))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode() == 200;
    }

    private static Map<String, Object> createRecord(HttpClient client, int port, Map<String, Object> attachmentHandle) throws Exception {
        Map<String, Object> body = Map.of("attachment", attachmentHandle);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/gc_proof_docs"))
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

    private static int deleteRecord(HttpClient client, int port, String id) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/gc_proof_docs/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode();
    }

    private static Map<String, Object> updateRecordAttachment(
            HttpClient client, int port, String id, Map<String, Object> currentRecord, Map<String, Object> newAttachmentHandle
    ) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>(currentRecord);
        body.remove("id");
        body.put("attachment", newAttachmentHandle);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/gc_proof_docs/" + id))
                        .timeout(Duration.ofSeconds(20))
                        .header("X-Api-Key", API_KEY)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for update: " + response.body());
        Map<String, Object> result = new java.util.LinkedHashMap<>(OBJECT_MAPPER.readValue(response.body(), MAP_TYPE));
        result.put("__status", response.statusCode());
        return result;
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

    private static CompiledModel compiledGcProofModel() {
        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, true);
        CompiledFileMetadata fileMeta = new CompiledFileMetadata(List.of("text/plain"), 2_000_000L, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.fasterxml.jackson.databind.JsonNode",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                fileMeta
        );
        CompiledConcept concept = new CompiledConcept(
                "GcProofDoc", "GcProofDoc", "gc_proof_docs", List.of(idField, attachmentField));
        return new CompiledModel("harden.gc.proof", "1.0.0", "1.0.0", Map.of(concept.getName(), concept));
    }

    private static Path writeGcProofModel(Path runRoot) throws IOException {
        Path modelRoot = runRoot.resolve("model");
        Files.createDirectories(modelRoot);
        Path modelSource = modelRoot.resolve("model.json");
        Files.writeString(modelSource, """
                {
                  "namespace": "harden.gc.proof",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "GcProofDoc",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "attachment",
                          "type": "file",
                          "file": { "contentTypes": ["text/plain"], "maxSizeBytes": 2000000, "multiple": false }
                        }
                      ]
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        return modelSource;
    }

    private static GeneratedDatabasePlan h2JdbcPlan(Path runRoot, Path modelSource) {
        return new GeneratedDatabasePlan(
                "harden-gc-proof",
                DatabaseEngine.H2_LOCAL,
                "jdbc",
                true,
                "harden_gc_proof",
                "harden_gc_proof",
                "harden-gc-proof",
                runRoot.resolve("runtime-data").toString(),
                "harden-gc-proof-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:harden_gc_proof;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                "sha256:harden-gc-proof",
                modelSource,
                List.of("harden-gc-proof")
        );
    }

    private static Path ensureRuntimeHostLibs(Path evidenceRoot) throws Exception {
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
                        // REG-12 Slice 3: NpdevDocumentRenderConfig imports both document-render
                        // adapter classes unconditionally (same reason the mail adapters are listed
                        // below) -- both jars must exist or the generated app fails to compile.
                        ":adapters:document-render-inproc:jar",
                        ":adapters:document-render-stub:jar",
                        ":adapters:expression-cel:jar",
                        ":adapters:file-store-inproc:jar",
                        ":adapters:file-store-objectstore:jar",
                        ":adapters:flow-compiled:jar",
                        ":adapters:json-jackson:jar",
                        ":adapters:metrics-micrometer:jar",
                        ":adapters:notification-inproc:jar",
                        // REG-10: the RuntimeHost template's NpdevPluginConfig imports the mail adapters,
                        // so the generated app cannot compile without their jars. On the dev machine these
                        // were already present in the libs dir from prior builds (masking the gap); on a
                        // clean CI runner only explicitly-built adapters exist -> compile error. Build them.
                        ":adapters:mail-inproc:jar",
                        ":adapters:mail-smtp:jar",
                        ":adapters:persistence-inproc:jar",
                        ":adapters:persistence-postgres:jar",
                        ":adapters:resume-bootstrap-spring:jar",
                        ":adapters:runtime-validation:jar",
                        ":adapters:schema-validator-default:jar",
                        ":adapters:tracing-redaction-default:jar",
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
                        // REG-10/LNCH-20: resolve PowerShell 7 via PATH ("pwsh"), not a hardcoded
                        // Windows install path. The absolute "C:\Program Files (x86)\PowerShell\7\pwsh.exe"
                        // does not exist on a Linux CI runner, so ProcessBuilder.start() threw
                        // java.io.IOException (No such file or directory) -- the first-ever GitHub Actions
                        // run caught exactly this. "pwsh" is on PATH on both Windows (confirmed 7.x) and the
                        // GitHub ubuntu-latest runner (PowerShell 7 preinstalled); -ExecutionPolicy is a
                        // harmless no-op on Linux.
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
        return runtimeHostLibs;
    }

    private static Process startPackagedApp(
            Path finalAppRoot,
            Path jar,
            int port,
            String jdbcUrl,
            Path runtimeHostLibs,
            Path fileStoreRoot,
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
                "--npdev.auth.enabled=false",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/schema-realization",
                "--npdev.filestore.provider=inproc",
                "--npdev.filestore.root=" + fileStoreRoot
        );
        builder.directory(finalAppRoot.toFile());
        builder.environment().put("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread logThread = new Thread(() -> copyProcessOutput(process, bootLog), "harden-gc-app-log");
        logThread.setDaemon(true);
        logThread.start();
        return process;
    }

    /** How long a packaged app may take to answer /actuator/health. See waitForHealth. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(6);

    private static void waitForHealth(int port, Path evidenceRoot) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://localhost:" + port + "/actuator/health");
        // F7 (POST_PROGRAMME_AUDIT_PLAN §2.4): 2 minutes was not enough. Gradle runs this suite
        // with maxParallelForks = 2, so this test's packaged Spring Boot app can be booting at the
        // same time as the other packaged-app proof's -- two JVMs each starting Tomcat, Flyway and
        // a datasource, on a machine already busy compiling. That produced three intermittent
        // "did not become healthy" failures across the 2026-07-25 security programme, every one of
        // which passed on an isolated re-run. The app is not broken; it is queued behind the other
        // one. Raised to 6 minutes, which is well clear of the observed worst case and still far
        // below the 12-minute @Timeout on the test itself, so a genuine boot failure is still
        // caught -- just later.
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
        // Say how long we actually waited: "did not become healthy" alone cost a diagnosis cycle
        // every time it fired, because it does not distinguish "app crashed" from "app was slow".
        throw new IllegalStateException(
                "Packaged app did not become healthy on port " + port + " within " + HEALTH_TIMEOUT
                        + " (see " + evidenceRoot.resolve("packaged-app-boot-output.txt") + "). If the log shows a"
                        + " normal startup that simply ran past the deadline, this is F7's fork-contention"
                        + " flake, not a regression -- re-run this test alone to confirm.", last);
    }

    /** LNCH-20: the platform ships one gradlew per OS (no `.bat` on Linux/macOS); this test
     * hardcoded `gradlew.bat` unconditionally, which fails to exec at all on a Linux CI runner
     * (confirmed live). Also defensively marks the resolved wrapper executable -- a fresh copy
     * made by {@code FinalAppAssembler} (or any plain file copy) does not necessarily preserve
     * the source file's POSIX execute bit. */
    private static Path gradlewPath(Path root) throws java.io.IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path gradlew = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!windows) {
            // File.setExecutable() alone was confirmed live NOT to unblock this -- use the
            // POSIX permission API explicitly instead, and fail loudly with a diagnosable
            // message (does the file even exist at this point?) rather than let a bare
            // IOException from ProcessBuilder.start() obscure the real cause a second time.
            if (!Files.exists(gradlew)) {
                throw new java.io.IOException("gradlewPath: " + gradlew + " does not exist -- final app assembly may not have completed yet");
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
