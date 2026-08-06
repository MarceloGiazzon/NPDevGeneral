package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

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

import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * HARDEN-OBJSTORE-P4: proves the full path (controller -&gt; config -&gt; adapter -&gt; object store)
 * end to end in a real packaged app -- generate a model with a genuine {@code file}-typed field,
 * assemble + boot the app configured for {@code npdev.filestore.provider=objectstore} against a
 * real MinIO endpoint, upload+download over HTTP, and assert bytes exist in the bucket under the
 * tenant prefix. Companion to {@link TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest}
 * (same generate-assemble-boot harness), scoped to the file-store surface only.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason =
        "Uses a MinIO Testcontainers Linux container; GitHub windows-latest runners cannot run Linux "
        + "containers (unlike Linux runners / local Docker Desktop). Validated by the green Linux CI "
        + "job. Windows-CI Docker-test scoping is tracked as REG-34.")
final class HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    private static final Path OUTSIDE_ROOT = WORKSPACE_ROOT.resolveSibling(WORKSPACE_ROOT.getFileName() + "__OutsideRepo");
    private static final Path HARDEN_ROOT = OUTSIDE_ROOT.resolve("harden-objstore-p4-packaged-generated-app-runtime");
    private static final String BUCKET = "npdev-files";
    private static final String API_KEY = "api-dev";
    private static final String TENANT = "dev";

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void packagedGeneratedAppUploadsAndDownloadsAFileThroughARealObjectStore() throws Exception {
        String runId = "harden-objstore-p4-" + System.currentTimeMillis();
        Path runRoot = HARDEN_ROOT.resolve(runId);
        MinIOContainer minio = new MinIOContainer("minio/minio:RELEASE.2024-08-29T01-40-52Z");
        minio.start();
        try {
            S3Client s3 = S3Client.builder()
                    .region(Region.US_EAST_1)
                    .endpointOverride(URI.create(minio.getS3URL()))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                    .build();
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            try {
                runProof(runId, runRoot, minio, s3);
            } finally {
                s3.close();
            }
        } finally {
            minio.stop();
            deleteRecursively(runRoot);
        }
    }

    private void runProof(String runId, Path runRoot, MinIOContainer minio, S3Client s3) throws Exception {
        Path generatedRoot = runRoot.resolve("generated-artifact");
        Path schemaRoot = generatedRoot.resolve("src/main/resources/db/schema-realization");
        Path finalAppRoot = runRoot.resolve("generated-app");
        Path evidenceRoot = runRoot.resolve("proof-output");
        Files.createDirectories(evidenceRoot);

        CompiledModel model = compiledObjstoreProofModel();
        Path modelSource = writeObjstoreProofModel(runRoot);
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
                // REG-137: pass both the env var AND -P. FinalAppAssembler always bakes a
                // generation-time npdevRuntimeHostLibsDir default into the assembled app's
                // gradle.properties (REG-128), which -- absent this -P -- would win over a
                // bare env var via Gradle's own command-line/-property-file precedence.
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
        Process app = startPackagedApp(finalAppRoot, jar, port, jdbcUrl, runtimeHostLibs, evidenceRoot, minio);
        try {
            waitForHealth(port, evidenceRoot);
            HttpClient client = HttpClient.newHttpClient();

            byte[] payload = "<script>alert(document.cookie)</script>".getBytes(StandardCharsets.UTF_8);
            Map<String, Object> uploadResponse = uploadFile(client, port, "ObjstoreProofDoc", "attachment",
                    "payload.html", "text/html", payload);
            String storeId = String.valueOf(uploadResponse.get("storeId"));
            String key = String.valueOf(uploadResponse.get("key"));
            assertEquals("file-store-objectstore", storeId, () -> "upload response: " + uploadResponse);
            assertTrue(key.startsWith(TENANT + "/"), () -> "key should be tenant-prefixed: " + uploadResponse);
            Files.writeString(evidenceRoot.resolve("upload-response.json"),
                    OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(uploadResponse), StandardCharsets.UTF_8);

            HttpResponse<byte[]> download = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files?storeId=" + storeId + "&key=" + key))
                            .timeout(Duration.ofSeconds(20))
                            .header("X-Api-Key", API_KEY)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(200, download.statusCode(), () -> "download response: " + download.body().length + " bytes");
            assertArrayEquals(payload, download.body(), "downloaded bytes must match the uploaded bytes exactly");
            String contentType = download.headers().firstValue("Content-Type").orElse("");
            String disposition = download.headers().firstValue("Content-Disposition").orElse("");
            String nosniff = download.headers().firstValue("X-Content-Type-Options").orElse("");
            String csp = download.headers().firstValue("Content-Security-Policy").orElse("");
            assertTrue(contentType.startsWith("text/html"), () -> "Content-Type: " + contentType);
            assertTrue(disposition.startsWith("attachment"), () -> "Content-Disposition: " + disposition);
            assertEquals("nosniff", nosniff);
            assertTrue(csp.contains("sandbox"), () -> "Content-Security-Policy: " + csp);

            List<S3Object> objectsUnderTenantPrefix = s3.listObjectsV2(
                    ListObjectsV2Request.builder().bucket(BUCKET).prefix(TENANT + "/").build()
            ).contents();
            assertEquals(1, objectsUnderTenantPrefix.size(),
                    () -> "expected exactly one object under the tenant prefix in the real bucket: " + objectsUnderTenantPrefix);
            assertEquals(key, objectsUnderTenantPrefix.get(0).key());

            Files.writeString(evidenceRoot.resolve("harden-objstore-p4-proof-output.txt"),
                    "MinIO endpoint: " + minio.getS3URL() + System.lineSeparator()
                            + "Bucket: " + BUCKET + System.lineSeparator()
                            + "Upload response: " + uploadResponse + System.lineSeparator()
                            + "Download Content-Type: " + contentType + System.lineSeparator()
                            + "Download Content-Disposition: " + disposition + System.lineSeparator()
                            + "Download X-Content-Type-Options: " + nosniff + System.lineSeparator()
                            + "Download Content-Security-Policy: " + csp + System.lineSeparator()
                            + "Downloaded bytes match uploaded bytes: PASS" + System.lineSeparator()
                            + "Object confirmed in bucket under tenant prefix: " + objectsUnderTenantPrefix.get(0).key() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } finally {
            app.destroy();
            if (!app.waitFor(15, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                app.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    private static Map<String, Object> uploadFile(
            HttpClient client, int port, String concept, String field, String filename, String contentType, byte[] payload
    ) throws Exception {
        String boundary = "npdev-proof-" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(payload);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/files/" + concept + "/" + field))
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

    private static CompiledModel compiledObjstoreProofModel() {
        CompiledField idField = new CompiledField("id", "uuid", "java.util.UUID", true, true, true);
        CompiledFileMetadata fileMeta = new CompiledFileMetadata(
                List.of("text/html", "image/png", "application/pdf"), 2_000_000L, false);
        CompiledField attachmentField = new CompiledField(
                "attachment", "file", "com.fasterxml.jackson.databind.JsonNode",
                false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, null,
                fileMeta
        );
        CompiledConcept concept = new CompiledConcept(
                "ObjstoreProofDoc", "ObjstoreProofDoc", "objstore_proof_docs", List.of(idField, attachmentField));
        return new CompiledModel("harden.objstore.p4", "1.0.0", "1.0.0", Map.of(concept.getName(), concept));
    }

    private static Path writeObjstoreProofModel(Path runRoot) throws IOException {
        Path modelRoot = runRoot.resolve("model");
        Files.createDirectories(modelRoot);
        Path modelSource = modelRoot.resolve("model.json");
        Files.writeString(modelSource, """
                {
                  "namespace": "harden.objstore.p4",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "ObjstoreProofDoc",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "attachment",
                          "type": "file",
                          "file": {
                            "contentTypes": ["text/html", "image/png", "application/pdf"],
                            "maxSizeBytes": 2000000,
                            "multiple": false
                          }
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
                "harden-objstore-p4",
                DatabaseEngine.H2_LOCAL,
                "jdbc",
                true,
                "harden_objstore_p4",
                "harden_objstore_p4",
                "harden-objstore-p4",
                runRoot.resolve("runtime-data").toString(),
                "harden-objstore-p4-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:harden_objstore_p4;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
                "sha256:harden-objstore-p4",
                modelSource,
                List.of("harden-objstore-p4")
        );
    }

    private static Path ensureRuntimeHostLibs(Path evidenceRoot) throws Exception {
        return withKernelBuildLock(() -> doEnsureRuntimeHostLibs(evidenceRoot));
    }

    /**
     * CI_RED_PLAN.md I1 (2026-08-05): {@code HardenGcDeleteReplaceCascade...}, {@code
     * HardenObjstoreFileUpload...}, and {@code TrustedSourceEmitter...} each call this method,
     * which spawns its own {@code --no-daemon} Gradle subprocess against the SAME NPDevKernel
     * project directory. {@code generator/build.gradle}'s {@code test} task runs with {@code
     * maxParallelForks = 2}, so two of these three classes can run concurrently in separate
     * forked JVMs -- two independent, uncoordinated Gradle processes writing to the same
     * incremental-compilation state (e.g. {@code
     * Build/gradle/npdev-kernel/adapters/authz-default/tmp/compileJava/previous-compilation-data.bin})
     * corrupts it for whichever one loses the race: {@code Cannot access output property
     * 'previousCompilationData' ... Failed to create MD5 hash for file ... as it does not
     * exist}. Reproduced live by running all three together: all three failed (with three
     * different symptoms) in the same run; the failure vanished running any one alone. A
     * cross-process file lock is required -- JUnit 5's own {@code @ResourceLock} only
     * coordinates within one JVM's thread pool, not across Gradle's separately forked test-worker
     * processes.
     */
    private static <T> T withKernelBuildLock(java.util.concurrent.Callable<T> action) throws Exception {
        java.nio.file.Path lockFile = WORKSPACE_ROOT.resolve("Build").resolve("npdev-kernel-adapter-build.lock");
        Files.createDirectories(lockFile.getParent());
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            return action.call();
        }
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
                        // REG-12 Slice 3: NpdevDocumentRenderConfig imports both document-render
                        // adapter classes unconditionally (same reason the mail adapters are listed
                        // below) -- both jars must exist or the generated app fails to compile.
                        ":adapters:document-render-inproc:jar",
                        ":adapters:document-render-stub:jar",
                        ":adapters:expression-cel:jar",
                        ":adapters:external-ai-http:jar",
                        ":adapters:external-ai-inproc:jar",
                        ":adapters:external-ai-pack-core:jar",
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
            Path evidenceRoot,
            MinIOContainer minio
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
                "--npdev.filestore.provider=objectstore",
                "--npdev.filestore.objectstore.bucket=" + BUCKET,
                "--npdev.filestore.objectstore.endpoint=" + minio.getS3URL(),
                "--npdev.filestore.objectstore.accessKeyId=" + minio.getUserName(),
                "--npdev.filestore.objectstore.secretAccessKey=" + minio.getPassword(),
                "--npdev.filestore.objectstore.pathStyleAccess=true"
        );
        builder.directory(finalAppRoot.toFile());
        builder.environment().put("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread logThread = new Thread(() -> copyProcessOutput(process, bootLog), "harden-objstore-p4-app-log");
        logThread.setDaemon(true);
        logThread.start();
        return process;
    }

    private static void waitForHealth(int port, Path evidenceRoot) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://localhost:" + port + "/actuator/health");
        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
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
        throw new IllegalStateException("Packaged app did not become healthy on port " + port, last);
    }

    /** LNCH-20: the platform ships one gradlew per OS (no `.bat` on Linux/macOS); this test
     * hardcoded `gradlew.bat` unconditionally, which fails to exec at all on a Linux CI runner
     * (confirmed live). Also defensively marks the resolved wrapper executable -- a fresh copy
     * made by {@code FinalAppAssembler} (or any plain file copy) does not necessarily preserve
     * the source file's POSIX execute bit. */
    private static Path gradlewPath(Path root) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path gradlew = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!windows) {
            gradlew.toFile().setExecutable(true);
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
