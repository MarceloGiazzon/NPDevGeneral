package com.npdev.generator.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stage 0 constitutional guardrail.
 *
 * The generated projection must stay thin glue. It must not hardcode
 * adapter/plugin implementation selection in generated Java sources.
 */
public final class GeneratedProjectionGuard {

    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("\\bnew\\s+[A-Za-z0-9_$.]*(Adapter|CapabilityAdapter)\\s*\\("),
            Pattern.compile("\\bif\\s*\\([^\\n\\r)]*adapterId[^\\n\\r)]*\\)"),
            Pattern.compile("\\bif\\s*\\([^\\n\\r)]*capability[^\\n\\r)]*adapter[^\\n\\r)]*\\)")
    );

    public void assertThinProjection(Path outRoot) throws IOException {
        if (outRoot == null || !Files.exists(outRoot)) {
            return;
        }

        Path javaRoot = outRoot.resolve("src/main/java");
        if (!Files.exists(javaRoot)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(javaRoot)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .collect(Collectors.toList())) {
                String source = Files.readString(path);
                for (Pattern pattern : FORBIDDEN_PATTERNS) {
                    if (pattern.matcher(source).find()) {
                        violations.add(path.toString() + " matches forbidden pattern: " + pattern.pattern());
                    }
                }
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "Generated projection violates thin-projection guard:\n - "
                            + String.join("\n - ", violations)
            );
        }
    }
}