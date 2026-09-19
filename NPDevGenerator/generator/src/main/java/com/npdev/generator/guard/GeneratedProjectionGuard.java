package com.npdev.generator.guard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
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

    private static final Pattern ADAPTER_INSTANTIATION_PATTERN =
            Pattern.compile("\\bnew\\s+([A-Za-z0-9_$.]*(?:Adapter|CapabilityAdapter))\\s*\\(");

    /**
     * Exact simple class names exempt from {@link #ADAPTER_INSTANTIATION_PATTERN} (REG-220):
     * {@code GeneratedActionCapabilityAdapter} is the platform's own single, always-identically-
     * shaped trusted-action bridge class ({@code TrustedActionSupportTemplates}), emitted once per
     * app and wired into the SAME {@code CapabilityRegistry} the kernel already treats as the real
     * pluggable-adapter seam -- constructing it is not a per-plugin adapter SELECTION (what this
     * guard exists to catch, e.g. choosing between {@code NotificationEmailAdapter} and
     * {@code NotificationSmsAdapter}), just wiring the one bridge every app gets identically.
     */
    private static final Set<String> KNOWN_SAFE_ADAPTER_CLASSES = Set.of(
            "GeneratedActionCapabilityAdapter"
    );

    private static final List<Pattern> OTHER_FORBIDDEN_PATTERNS = List.of(
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
                Matcher adapterMatcher = ADAPTER_INSTANTIATION_PATTERN.matcher(source);
                while (adapterMatcher.find()) {
                    String matchedClass = adapterMatcher.group(1);
                    String simpleName = matchedClass.substring(matchedClass.lastIndexOf('.') + 1);
                    if (!KNOWN_SAFE_ADAPTER_CLASSES.contains(simpleName)) {
                        violations.add(path.toString() + " matches forbidden pattern: "
                                + ADAPTER_INSTANTIATION_PATTERN.pattern());
                        break;
                    }
                }
                for (Pattern pattern : OTHER_FORBIDDEN_PATTERNS) {
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