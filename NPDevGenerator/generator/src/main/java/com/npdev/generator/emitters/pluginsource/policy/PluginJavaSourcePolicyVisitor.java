package com.npdev.generator.emitters.pluginsource.policy;

import com.npdev.kernel.security.TrustedSourceBytecodeInspector;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreeScanner;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AST policy scanner for mounted plugin Java ({@code plugin:java-source} /
 * {@code plugin:java-controller}): walks a parsed compilation unit and records every escape
 * vector it finds, mirroring the bytecode-level denylist of the shared
 * {@code com.npdev.kernel.security.TrustedSourceBytecodeInspector} in source form.
 *
 * <p>Deliberately DIFFERENT from {@code TrustedJavaSourcePolicyVisitor} (the trusted-source
 * PROCEDURE policy): plugin code is real application code -- it may declare packages, import
 * {@code org.springframework.*} / {@code com.npdev.generated.*}, use Lombok-free ordinary Java.
 * Only the escape references are refused, never the surrounding idiom. Rule sets are DERIVED at
 * class load from the kernel inspector's constants so the source gate and the boot-time bytecode
 * gate cannot drift apart.
 */
public final class PluginJavaSourcePolicyVisitor extends TreeScanner<Void, Void> {
    private final List<String> violations;

    public PluginJavaSourcePolicyVisitor(List<String> violations) {
        this.violations = violations;
    }

    @Override
    public Void visitImport(ImportTree node, Void unused) {
        String qualified = node.getQualifiedIdentifier().toString();
        if (node.isStatic()) {
            if (isForbiddenSystemMethod(qualified)) {
                violations.add("forbidden static import " + qualified);
            }
            return super.visitImport(node, unused);
        }
        if (qualified.endsWith(".*")) {
            for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
                if (qualified.startsWith(prefix)) {
                    violations.add("forbidden wildcard import " + qualified);
                }
            }
            return super.visitImport(node, unused);
        }
        if (FORBIDDEN_IMPORTS.contains(qualified)) {
            violations.add("forbidden import " + qualified);
        }
        for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
            if (qualified.startsWith(prefix)) {
                violations.add("forbidden import " + qualified);
            }
        }
        return super.visitImport(node, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
        String name = node.getName().toString();
        if (FORBIDDEN_IDENTIFIERS.contains(name)) {
            violations.add("forbidden identifier " + name);
        }
        return super.visitIdentifier(node, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        String selected = node.toString();
        if (isForbiddenQualifiedUse(selected)) {
            violations.add("forbidden qualified use " + selected);
        }
        if (isForbiddenSystemMethod(selected)) {
            violations.add("forbidden method call " + selected);
        }
        if (FORBIDDEN_IDENTIFIERS.contains(node.getIdentifier().toString())) {
            violations.add("forbidden member " + node.getIdentifier());
        }
        return super.visitMemberSelect(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
        String type = node.getIdentifier().toString();
        if (FORBIDDEN_IDENTIFIERS.contains(type) || isForbiddenQualifiedUse(type)) {
            violations.add("forbidden constructor " + type);
        }
        return super.visitNewClass(node, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        String select = node.getMethodSelect().toString();
        if (isForbiddenSystemMethod(select) || isForbiddenQualifiedUse(select)) {
            violations.add("forbidden method call " + select);
        }
        return super.visitMethodInvocation(node, unused);
    }

    private static boolean isForbiddenSystemMethod(String value) {
        for (String select : FORBIDDEN_SYSTEM_SELECTS) {
            if (value.equals(select) || value.endsWith("." + select)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isForbiddenQualifiedUse(String value) {
        for (String prefix : FORBIDDEN_IMPORT_PREFIXES) {
            if (value.startsWith(prefix) || value.contains("." + prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Import prefixes refused outright -- derived from the kernel inspector's owner prefixes. */
    public static final Set<String> FORBIDDEN_IMPORT_PREFIXES = TrustedSourceBytecodeInspector.FORBIDDEN_OWNER_PREFIXES.stream()
            .map(prefix -> prefix.replace('/', '.'))
            .collect(Collectors.toUnmodifiableSet());

    /** Single-type imports refused outright -- derived from the kernel inspector's exact owners. */
    public static final Set<String> FORBIDDEN_IMPORTS = TrustedSourceBytecodeInspector.FORBIDDEN_OWNERS.stream()
            .map(owner -> owner.replace('/', '.'))
            .collect(Collectors.toUnmodifiableSet());

    /** Member-select forms of the refused {@code java.lang.System} methods. */
    public static final Set<String> FORBIDDEN_SYSTEM_SELECTS = TrustedSourceBytecodeInspector.FORBIDDEN_SYSTEM_METHODS.stream()
            .map(method -> method.substring(method.lastIndexOf('/') + 1))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Simple names refused wherever they appear (identifier, member select, constructor). Includes
     * the exact-owner classes from the kernel inspector plus the well-known escape classes under
     * the banned prefixes (reachable via wildcard imports, which the bytecode gate still catches
     * by constant-pool footprint -- this list keeps the source gate consistent with it).
     */
    public static final Set<String> FORBIDDEN_IDENTIFIERS = Set.of(
            "Runtime",
            "Process",
            "ProcessBuilder",
            "Class",
            "ClassLoader",
            "ServiceLoader",
            "Thread",
            "ThreadLocal",
            "Timer",
            "Executor",
            "ExecutorService",
            "Executors",
            "ThreadPoolExecutor",
            "ScheduledThreadPoolExecutor",
            "ScheduledExecutorService",
            "ForkJoinPool",
            "ForkJoinTask",
            "CompletableFuture",
            "CompletionService",
            "CompletionStage",
            "FutureTask",
            "Delayed",
            "ScheduledFuture",
            "TimeUnit",
            "File",
            "Files",
            "Path",
            "Paths",
            "URL",
            "URI",
            "Socket",
            "ServerSocket",
            "HttpClient",
            "Method",
            "Field",
            "Constructor",
            "AccessibleObject",
            "MethodHandles",
            "ScriptEngine",
            "ScriptEngineManager"
    );
}