package com.npdev.generator.provenance;

import com.npdev.dsl.v1.compiled.CompiledModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Emits {@code npdev-build-info.properties} at the root of an assembled FinalApp's own classpath
 * (its {@code src/main/resources}, NOT the generator's deterministic artifact tree): real provenance
 * (which commit/branch of the platform generated this, the app's own declared version, and the
 * generation timestamp) for the existing runtime consumers that previously always read it as
 * UNKNOWN/MISSING ({@code GeneratedBuildInfoLogger}, {@code NpdevBuildInfoInfoContributor}).
 *
 * <p>This deliberately runs at FINAL APP ASSEMBLY time, after {@code FinalAppAssembler}, not inside
 * {@code GeneratorFacade.generate()} — the generator's own output root must stay 100% deterministic
 * (no timestamps), a boundary already enforced by {@code GeneratorTestModelLoaderTest} forbidding
 * exactly this filename, a sibling {@code generated-stamp.properties}, and a generated
 * {@code GeneratedBuildInfo.java} from ever appearing there.</p>
 */
public final class BuildInfoEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/npdev-build-info.properties";
    private static final String GENERATOR_VERSION = "0.1.0";
    private static final String UNKNOWN = "UNKNOWN";

    public void emit(CompiledModel model, Path finalAppRoot) throws IOException {
        String nowUtc = Instant.now().toString();
        GitInfo git = resolveGitInfo();

        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("npdev.version", valueOrUnknown(model == null ? null : model.getVersion()));
        properties.put("npdev.namespace", valueOrUnknown(model == null ? null : model.getNamespace()));
        properties.put("npdev.commit", git.commit());
        properties.put("npdev.builtAt", nowUtc);
        properties.put("npdev.generator.version", GENERATOR_VERSION);
        properties.put("npdev.generator.tag", git.branch());
        // Back-compat key: GeneratedBuildInfoLogger reads generatedAtUtc, not builtAt.
        properties.put("npdev.generator.generatedAtUtc", nowUtc);

        Path target = finalAppRoot.resolve(RELATIVE_PATH);
        Files.createDirectories(target.getParent());
        Files.writeString(
                target,
                toPropertiesText(properties),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static String valueOrUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }

    private static String toPropertiesText(Map<String, String> properties) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    /**
     * Best-effort: if the generator isn't running from inside a git checkout of the NPDev workspace
     * (or {@code git} isn't on PATH), every field falls back to UNKNOWN rather than failing
     * generation — provenance is informational, never a hard generation dependency.
     */
    private static GitInfo resolveGitInfo() {
        Path workspaceRoot = locateWorkspaceRoot(Path.of("").toAbsolutePath().normalize());
        if (workspaceRoot == null) {
            return new GitInfo(UNKNOWN, UNKNOWN);
        }
        String commit = runGit(workspaceRoot, "rev-parse", "--short", "HEAD");
        String branch = runGit(workspaceRoot, "rev-parse", "--abbrev-ref", "HEAD");
        return new GitInfo(commit == null ? UNKNOWN : commit, branch == null ? UNKNOWN : branch);
    }

    private static Path locateWorkspaceRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private static String runGit(Path workingDirectory, String... args) {
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command().add("git");
            for (String arg : args) {
                builder.command().add(arg);
            }
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            return output;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            return null;
        }
    }

    private record GitInfo(String commit, String branch) {
    }
}
