package com.npdev.generator.emitters;

import com.npdev.generator.emitters.pluginsource.policy.PluginJavaSourcePolicyVisitor;
import com.npdev.generator.emitters.trustedsource.compile.InMemoryTrustedJavaSource;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Plugin Java source AST validation ({@code plugin:java-source} / {@code plugin:java-controller}
 * mounts): parses each emitted plugin file with the platform compiler and refuses generation if
 * the source references capability escapes (filesystem/network IO, process/system control,
 * reflection, dynamic loading, threads, scripting, detached async work, JVM internals). SEC-3 / B30
 * generation-side admission; the app's boot-time bytecode gate
 * ({@code PluginBytecodeBootGate}, NPDevRuntimeHost) is the compiled-form twin of this check.
 *
 * <p>Unlike {@code TrustedJavaSourcePolicy} (the trusted-source PROCEDURE policy), plugin code is
 * real application code: package declarations, {@code org.springframework.*} /
 * {@code com.npdev.generated.*} imports and ordinary Java idiom are all allowed -- only the escape
 * references are refused, using rule sets derived from the shared kernel bytecode inspector so the
 * source gate and the boot gate cannot drift apart.
 */
public final class PluginJavaSourcePolicy {

    private PluginJavaSourcePolicy() {
    }

    public static void validatePluginJavaSource(String source, String relativePath) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Plugin Java source AST validation requires a JDK compiler: " + relativePath);
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
                new PluginJavaSourcePolicyVisitor(violations).scan(unit, null);
            }
            if (!violations.isEmpty()) {
                throw new IllegalStateException(
                        "Forbidden plugin Java source use in " + relativePath + ": " + String.join("; ", violations)
                );
            }
        }
        catch (IOException ex) {
            throw new IllegalStateException("Plugin Java source AST validation failed for " + relativePath + ": " + ex.getMessage(), ex);
        }
        String syntaxErrors = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .map(diagnostic -> "line " + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        if (!syntaxErrors.isBlank()) {
            throw new IllegalStateException("Plugin Java source syntax error in " + relativePath + ": " + syntaxErrors);
        }
    }
}