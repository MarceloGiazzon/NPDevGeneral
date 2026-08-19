package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1.4 enforcement: no ERROR diagnostic may ship without an actionable {@code suggestedFix}.
 *
 * <p><b>Why the corpus is this package's own source and not {@code golden-ai-scenarios/}.</b> The
 * roadmap item specified "a corpus run over the 28 golden-scenario expected failures". Measured
 * 2026-08-18: {@code golden-ai-scenarios/} holds 28 scenarios of which 23 (not 28) declare
 * {@code expectedOutcome: fail}, and NONE of the 23 fails inside this validator. Their models are
 * {@code schemaVersion: ai-model.v1} -- a different schema, which {@code JsonModelParser} cannot
 * even parse as a DSL model -- and their expected diagnostic codes
 * ({@code AI_MODEL_KIND_UNSUPPORTED}, {@code PANEL_ENTITY_UNRESOLVED}, ...) are produced by the AI
 * admission layer at the {@code ai-model-schema} / {@code ai-config-schema} /
 * {@code command-policy} / {@code verification-schema} stages. A run over them would have asserted
 * 100% coverage of ZERO diagnostics and passed forever.
 *
 * <p>So the corpus is every {@code errors.add(...)} site in this package -- 362 of them, measured
 * the same day -- reconstructed into its message template and pushed through the same normalizer
 * the live validator uses. This is strictly stronger than a fixture corpus: a new ERROR rule is
 * covered the moment it is written, without anyone remembering to author a model that triggers it.
 */
class DiagnosticSuggestedFixCoverageTest {

    private static final Path VALIDATION_SOURCES = resolveWorkspaceRoot()
            .resolve("NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation");

    /**
     * The two sites that pass a variable rather than any string literal, so no template can be
     * reconstructed from source. Both are covered by {@link #literalLessSitesAreCoveredByTheirRealMessages}
     * with their real runtime shapes instead. Frozen at 2: this may only go DOWN. A third such site
     * fails here, which is the point -- a message assembled entirely off-site is invisible to the
     * scan and has to be re-declared here deliberately.
     */
    private static final int FROZEN_LITERAL_LESS_SITE_COUNT = 2;

    @Test
    void everyErrorMessageTemplateInThisPackageDerivesAnActionableSuggestedFix() throws IOException {
        List<Site> sites = collectErrorSites();
        assertFalse(sites.isEmpty(), "found no errors.add(...) sites -- the scan is broken, not the code");

        List<String> bare = new ArrayList<>();
        int checked = 0;
        for (Site site : sites) {
            if (site.template.isBlank()) {
                continue;
            }
            checked++;
            ValidationDiagnostic diagnostic =
                    ValidationDiagnosticNormalizer.semanticDiagnostic(site.template, ValidationSeverity.ERROR);
            String fix = diagnostic.getSuggestedFix();
            if (fix == null || fix.isBlank()
                    || ValidationDiagnosticNormalizer.UNCLASSIFIED_SUGGESTED_FIX.equals(fix)) {
                bare.add(site.file + ":" + site.line + "  " + site.template);
            }
        }

        assertTrue(checked > 300,
                "expected the scan to reach the whole package (362 sites measured 2026-08-18), reached " + checked);
        assertTrue(bare.isEmpty(),
                "ERROR diagnostics with no actionable suggestedFix (" + bare.size() + " of " + checked
                        + "). Either give the rule a '-- suggestedFix: <what to change>' marker in its own "
                        + "message, or teach ValidationDiagnosticNormalizer.deriveSuggestedFix the shape:\n  "
                        + String.join("\n  ", bare));
    }

    @Test
    void literalLessSitesAreCoveredByTheirRealMessages() throws IOException {
        List<Site> sites = collectErrorSites();
        long literalLess = sites.stream().filter(site -> site.template.isBlank()).count();
        assertEquals(FROZEN_LITERAL_LESS_SITE_COUNT, literalLess,
                "errors.add sites that pass a variable with no string literal; declare a real message "
                        + "for any new one below and lower/raise this deliberately");

        // ReferenceIntegrityValidation.describe(edge), both of its branches, and the
        // ModelResolutionException text SemanticValidator re-emits verbatim (ModelResolver's
        // "<Kind> not found: <name>" family).
        for (String message : List.of(
                "Panel ticketBoard layout.fields: references unknown field ownerId on concept Ticket",
                "Panel ticketBoard action.inputFields: references unknown query OpenTickets",
                "Concept not found: Ticket",
                "Capability not found: notify")) {
            ValidationDiagnostic diagnostic =
                    ValidationDiagnosticNormalizer.semanticDiagnostic(message, ValidationSeverity.ERROR);
            assertNotBare(diagnostic, message);
        }
    }

    /**
     * The end-to-end half: a real model through the real parser and validator, proving the templates
     * above are not passing on a shape the live pipeline never produces.
     */
    @Test
    void everyErrorDiagnosticFromABrokenModelCarriesAnActionableSuggestedFix() throws Exception {
        Path modelPath = Files.createTempFile("npdev-r14-suggested-fix-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "validation.suggestedfix.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Ticket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "ownerId", "type": "reference", "ref": "Ghost", "required": true },
                        { "name": "status", "type": "enum", "enumValues": ["open", "open"] },
                        { "name": "ownerId", "type": "string" }
                      ]
                    },
                    {
                      "name": "Ticket",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Audit",
                      "extends": "NoSuchBase",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "id2", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult result = new SemanticValidator().validateWithWarnings(ast);

        assertTrue(result.hasErrors(), "the fixture must actually fail validation, else this proves nothing");
        List<ValidationDiagnostic> errors = result.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getSeverity() == ValidationSeverity.ERROR)
                .toList();
        assertFalse(errors.isEmpty(), "expected ERROR diagnostics from the broken fixture");
        for (ValidationDiagnostic diagnostic : errors) {
            assertNotBare(diagnostic, diagnostic.getMessage());
            assertTrue(diagnostic.getHelpKey() != null && !diagnostic.getHelpKey().isBlank(),
                    "ERROR diagnostic with no helpKey: " + diagnostic.getMessage());
        }
    }

    /**
     * {@code helpKey} points at a knowledge card only where the card FILE exists -- a dangling id
     * would send {@code npdev_search_fix} looking for precedent that was never written.
     */
    @Test
    void knowledgeCardHelpKeysNameCardsThatExist() {
        Path cards = resolveWorkspaceRoot().resolve("knowledge/cards");
        for (String message : List.of(
                "Panel ticketBoard dataSource main: concept not found: Ghost",
                "Concept Ticket lifecycle: transition to 'archived' is not declared in lifecycle.states",
                "Concept Ticket field createdAt: ui.widget \"checkbox\" is incompatible with type date")) {
            ValidationDiagnostic diagnostic =
                    ValidationDiagnosticNormalizer.semanticDiagnostic(message, ValidationSeverity.ERROR);
            String helpKey = diagnostic.getHelpKey();
            assertFalse(helpKey.startsWith("validation."),
                    "expected a knowledge-card id for: " + message + ", got " + helpKey);
            assertTrue(Files.exists(cards.resolve(helpKey + ".json")),
                    "helpKey '" + helpKey + "' names no card under " + cards);
        }
    }

    private static void assertNotBare(ValidationDiagnostic diagnostic, String context) {
        String fix = diagnostic.getSuggestedFix();
        assertTrue(fix != null && !fix.isBlank(), "no suggestedFix for: " + context);
        assertFalse(ValidationDiagnosticNormalizer.UNCLASSIFIED_SUGGESTED_FIX.equals(fix),
                "unclassified (bare) suggestedFix for: " + context);
    }

    // ---------------------------------------------------------------------------------------
    // Source scan
    // ---------------------------------------------------------------------------------------

    private record Site(String file, int line, String template) {
    }

    private static List<Site> collectErrorSites() throws IOException {
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> files = Files.list(VALIDATION_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                int at = 0;
                while ((at = source.indexOf("errors.add(", at)) >= 0) {
                    int argumentStart = at + "errors.add(".length();
                    String argument = argumentText(source, argumentStart);
                    int line = (int) source.substring(0, at).chars().filter(ch -> ch == '\n').count() + 1;
                    sites.add(new Site(file.getFileName().toString(), line, templateOf(argument)));
                    at = argumentStart;
                }
            }
        }
        return sites;
    }

    /** The text of the call argument, stopping at the paren that closes {@code errors.add(}. */
    private static String argumentText(String source, int start) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = endOfStringLiteral(source, i);
                out.append(source, i, end + 1);
                i = end + 1;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return out.toString();
                }
                depth--;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Reconstructs the runtime message shape: literals verbatim, and one {@code Xname} stand-in
     * wherever an interpolated expression sat, so {@code "Aggregate " + name + ": root concept not
     * found: " + root} becomes {@code Aggregate Xname: root concept not found: Xname}.
     */
    private static String templateOf(String argument) {
        StringBuilder out = new StringBuilder();
        boolean pendingExpression = false;
        int i = 0;
        while (i < argument.length()) {
            char c = argument.charAt(i);
            if (c == '"') {
                int end = endOfStringLiteral(argument, i);
                if (pendingExpression) {
                    out.append("Xname");
                    pendingExpression = false;
                }
                out.append(unescape(argument.substring(i + 1, end)));
                i = end + 1;
                continue;
            }
            if (!Character.isWhitespace(c) && c != '+') {
                pendingExpression = true;
            }
            i++;
        }
        if (pendingExpression && out.length() > 0) {
            out.append("Xname");
        }
        return out.toString().trim();
    }

    private static int endOfStringLiteral(String source, int quoteIndex) {
        int i = quoteIndex + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i;
            }
            i++;
        }
        return source.length() - 1;
    }

    private static String unescape(String literal) {
        StringBuilder out = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' && i + 1 < literal.length()) {
                char next = literal.charAt(++i);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> next;
                });
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Same predicate as {@code AiModelToDslMappingTest}: identify the root by its CONTENTS. */
    private static Path resolveWorkspaceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path candidate = cwd;
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("NPDevContract"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevKernel"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to resolve workspace root from " + cwd);
    }
}
