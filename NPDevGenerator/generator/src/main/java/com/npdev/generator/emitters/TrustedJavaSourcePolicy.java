package com.npdev.generator.emitters;

import com.npdev.generator.emitters.trustedsource.compile.InMemoryTrustedJavaSource;
import com.npdev.generator.emitters.trustedsource.policy.TrustedJavaSourcePolicyVisitor;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.util.JavacTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Trusted-source Java procedure AST validation: parses a candidate procedure source with the
 * platform compiler and rejects it (throwing) if its imports or constructs fall outside the
 * sanctioned trusted-source subset.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedJavaSourcePolicy {

    private static final Set<String> ALLOWED_JAVA_IMPORTS = Set.of(
            "java.util.List",
            "java.util.Map",
            "java.util.Set",
            "java.util.Optional",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.time.Instant"
    );

    private static final Set<String> FORBIDDEN_JAVA_IMPORT_PREFIXES = Set.of(
            "java.io.",
            "java.nio.file.",
            "java.net.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.util.concurrent.",
            "javax.script.",
            "sun.",
            "jdk.",
            "org.",
            "com."
    );

    private TrustedJavaSourcePolicy() {
    }

    static void validateJavaSource(String source, String relativePath) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Trusted Java source AST validation requires a JDK compiler: " + relativePath);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavaFileObject sourceFile = new InMemoryTrustedJavaSource(relativePath, source);
            JavacTask task = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("-proc:none"),
                    null,
                    List.of(sourceFile)
            );
            Iterable<? extends CompilationUnitTree> units = task.parse();
            List<String> violations = new ArrayList<>();
            for (CompilationUnitTree unit : units) {
                if (unit.getPackageName() != null) {
                    violations.add("package declaration");
                }
                for (ImportTree importTree : unit.getImports()) {
                    validateJavaImport(relativePath, importTree, violations);
                }
                new TrustedJavaSourcePolicyVisitor(violations).scan(unit, null);
            }
            if (!violations.isEmpty()) {
                throw new IllegalStateException("Forbidden Java source use in " + relativePath + ": " + String.join("; ", violations));
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Trusted Java source AST validation failed for " + relativePath + ": " + ex.getMessage(), ex);
        }
        String syntaxErrors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        if (!syntaxErrors.isBlank()) {
            throw new IllegalStateException("Trusted Java source syntax error in " + relativePath + ": " + syntaxErrors);
        }
    }

    private static void validateJavaImport(String relativePath, ImportTree importTree, List<String> violations) {
        String importName = importTree.getQualifiedIdentifier().toString();
        if (importTree.isStatic()) {
            violations.add("static import " + importName);
            return;
        }
        if (importName.endsWith(".*")) {
            violations.add("wildcard import " + importName);
            return;
        }
        for (String prefix : FORBIDDEN_JAVA_IMPORT_PREFIXES) {
            if (importName.startsWith(prefix)) {
                violations.add("forbidden import " + importName);
                return;
            }
        }
        if (!ALLOWED_JAVA_IMPORTS.contains(importName)) {
            violations.add("non-allowlisted import " + importName);
        }
    }
}
