package com.npdev.generator.dbconfig;

import com.npdev.generator.testsupport.WorkspaceRootLocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 T10 follow-up (GATE-DET-1 as a bug <i>class</i>). Fails loudly if any emitter that writes a
 * JSON manifest/report consumed by a generated app or its runtime builds a nested map with a
 * multi-entry {@code java.util.Map.of(...)}.
 *
 * <p><b>Why this matters.</b> {@code Map.of(...)} with 2+ entries returns an
 * {@code ImmutableCollections.MapN} whose iteration order is randomized <i>per JVM process</i> by
 * {@code ImmutableCollections.SALT}. Jackson serializes maps in iteration order, and each generation
 * run is a fresh JVM, so such a map emits its keys in a run-to-run varying order -- byte-
 * nondeterministic generated output. That is exactly GATE-DET-1: one site
 * ({@code FinalAppAssembler}'s {@code storageBoundary}) was fixed under T10, then five more of the
 * same shape were found in this package. This test exists so a seventh cannot appear silently.
 *
 * <p><b>Scope.</b> The two packages that contain every emitter proven to write JSON manifests/reports
 * consumed by a generated app or its runtime: {@code com.npdev.generator.assembly} and
 * {@code com.npdev.generator.dbconfig}. A wider scan would flag {@code Map.of} calls that never reach
 * a serialized artifact (config lookups, test fixtures), whose ordering is irrelevant.
 *
 * <p><b>What is allowed.</b> {@code Map.of()} (empty -- nothing to iterate) and {@code Map.of(k, v)}
 * (single entry -- only one possible order). Both have no ordering ambiguity. Only 2+ entries
 * (4+ raw varargs to the {@code Map.of(k1,v1,...)} overload) are flagged.
 *
 * <p>Like {@link PlatformColumnContractTest}, this parses Java source as text against a known
 * repo-relative path rather than doing real static analysis -- the precedent for "assert against a
 * path in the repo" drift guards. Comments and string/char literals are stripped before scanning so
 * a {@code Map.of(...)} mentioned in a comment (as several of the fix-site comments now do) or inside
 * a string does not false-positive.
 */
final class NoMultiEntryMapOfInGeneratedManifestEmittersTest {

    private static final List<Path> SCANNED_PACKAGES = List.of(
            Path.of("NPDevGenerator", "generator", "src", "main", "java",
                    "com", "npdev", "generator", "assembly"),
            Path.of("NPDevGenerator", "generator", "src", "main", "java",
                    "com", "npdev", "generator", "dbconfig"));

    @Test
    @DisplayName("No emitter in assembly/ or dbconfig/ may build a nested map with a multi-entry "
            + "Map.of(...) -- ImmutableCollections.SALT randomizes its iteration order per-JVM, which "
            + "Jackson then serializes as byte-nondeterministic manifest output (GATE-DET-1)")
    void noMultiEntryMapOfInManifestEmitters() throws IOException {
        Path workspaceRoot = WorkspaceRootLocator.resolveWorkspaceRoot();
        List<String> violations = new ArrayList<>();
        int filesScanned = 0;

        for (Path packageRel : SCANNED_PACKAGES) {
            Path packageDir = workspaceRoot.resolve(packageRel);
            assertTrue(Files.isDirectory(packageDir),
                    "expected to scan " + packageDir + " but it is not a directory -- if the package "
                            + "moved, update NoMultiEntryMapOfInGeneratedManifestEmittersTest.SCANNED_PACKAGES");
            try (Stream<Path> files = Files.walk(packageDir)) {
                List<Path> javaFiles = files
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".java"))
                        .sorted()
                        .toList();
                for (Path javaFile : javaFiles) {
                    filesScanned++;
                    String stripped = stripCommentsAndLiterals(Files.readString(javaFile));
                    collectMultiEntryMapOf(stripped, workspaceRoot.relativize(javaFile).toString(), violations);
                }
            }
        }

        assertTrue(filesScanned > 0,
                "scanned zero .java files -- SCANNED_PACKAGES resolved to empty directories, which would "
                        + "make this conformance test vacuously green");

        assertTrue(violations.isEmpty(),
                "Found multi-entry Map.of(...) in a manifest/report emitter -- this is byte-"
                        + "nondeterministic generated output (GATE-DET-1). Offending site(s):\n"
                        + String.join("\n", violations) + "\n\n" + fixInstruction());
    }

    private static String fixInstruction() {
        return "What to do instead: replace the Map.of(...) with an insertion-ordered LinkedHashMap "
                + "(new LinkedHashMap<>() then .put(k, v) in the same key order), exactly as "
                + "FinalAppAssembler's storageBoundary and SchemaRealizationEmitter's schemaLifecycle/"
                + "sourceOfTruth/dbeaver were fixed. Map.of() (empty) and Map.of(k, v) (single entry) "
                + "are fine and are not flagged; only 2+ entries randomize their order via "
                + "ImmutableCollections.SALT.";
    }

    /**
     * Finds every {@code Map.of(} in already-comment/literal-stripped source and, for each, parses
     * forward through the balanced parentheses counting top-level comma-separated arguments. 4+ raw
     * arguments means 2+ key-value pairs under the {@code Map.of(k1,v1,k2,v2,...)} varargs overload.
     * (The {@code Map.ofEntries(...)}/{@code Map.entry(...)} form is not used anywhere in the scanned
     * scope; if one is ever introduced it will not match {@code Map.of(} and this guard would need
     * extending -- called out here rather than silently assumed.)
     */
    private static void collectMultiEntryMapOf(String stripped, String displayPath, List<String> violations) {
        int index = 0;
        while ((index = stripped.indexOf("Map.of(", index)) >= 0) {
            // Guard against matching e.g. "SomeMap.of(" where a longer identifier precedes "Map".
            if (index > 0 && (Character.isJavaIdentifierPart(stripped.charAt(index - 1))
                    || stripped.charAt(index - 1) == '.')) {
                index += "Map.of(".length();
                continue;
            }
            int openParen = index + "Map.of".length(); // points at '('
            int topLevelArgs = countTopLevelArgs(stripped, openParen);
            if (topLevelArgs >= 4) {
                int line = 1 + (int) stripped.substring(0, index).chars().filter(c -> c == '\n').count();
                violations.add("  " + displayPath + ":" + line + " -- Map.of(...) with "
                        + (topLevelArgs / 2) + " key-value pairs (" + topLevelArgs + " raw arguments)");
            }
            index = openParen;
        }
    }

    /**
     * Given an index pointing at the opening {@code '('} of a call, returns the number of top-level
     * (depth-1) comma-separated arguments, ignoring commas nested inside parentheses/brackets/braces.
     * Returns 0 for an empty argument list. Assumes comments and string/char literals have already
     * been stripped, so no in-string bookkeeping is needed here.
     */
    private static int countTopLevelArgs(String text, int openParenIndex) {
        int depth = 0;
        int commas = 0;
        boolean sawContent = false;
        for (int i = openParenIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '(', '[', '{' -> depth++;
                case ')', ']', '}' -> {
                    depth--;
                    if (depth == 0) {
                        return sawContent ? commas + 1 : 0;
                    }
                }
                case ',' -> {
                    if (depth == 1) {
                        commas++;
                    }
                }
                default -> {
                    if (depth >= 1 && !Character.isWhitespace(c)) {
                        sawContent = true;
                    }
                }
            }
        }
        return sawContent ? commas + 1 : 0;
    }

    /**
     * Replaces the contents of {@code //}/{@code /*}{@code *}{@code /} comments and of
     * {@code "..."}/{@code '...'} literals with spaces, preserving newlines (so reported line numbers
     * stay accurate) and preserving structural punctuation outside them. Text-block ({@code """})
     * handling: the opening/closing delimiters are treated as normal string literals character by
     * character, which is sufficient here because none of the scanned emitters embed {@code Map.of(}
     * inside a text block.
     */
    private static String stripCommentsAndLiterals(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < n && !(source.charAt(i) == '*' && i + 1 < n && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < n) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"') {
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                }
                if (i < n) {
                    out.append(' ');
                    i++;
                }
            } else if (c == '\'') {
                out.append(' ');
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        out.append("  ");
                        i += 2;
                    } else {
                        out.append(' ');
                        i++;
                    }
                }
                if (i < n) {
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
