package com.npdev.generator.emitters.trustedsource.policy;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreeScanner;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.lang.model.element.Modifier;

/**
 * AST policy scanner for trusted-source Java procedures: walks a parsed compilation unit and
 * records every forbidden construct (escape-hatch identifiers, reflective/qualified access,
 * forbidden method selects, static initializers, native/synchronized methods) it finds.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) -- self-contained, zero coupling to the
 * rest of the emitter beyond the violations list its caller supplies.
 */
public final class TrustedJavaSourcePolicyVisitor extends TreeScanner<Void, Void> {
    private final List<String> violations;

    public TrustedJavaSourcePolicyVisitor(List<String> violations) {
        this.violations = violations;
    }

    @Override
    public Void visitBlock(BlockTree node, Void unused) {
        if (node.isStatic()) {
            violations.add("static initializer");
        }
        return super.visitBlock(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        ModifiersTree modifiers = node.getModifiers();
        EnumSet<Modifier> forbidden = EnumSet.of(Modifier.NATIVE, Modifier.SYNCHRONIZED);
        for (Modifier modifier : modifiers.getFlags()) {
            if (forbidden.contains(modifier)) {
                violations.add("forbidden method modifier " + modifier.name().toLowerCase(Locale.ROOT));
            }
        }
        return super.visitMethod(node, unused);
    }

    @Override
    public Void visitIdentifier(IdentifierTree node, Void unused) {
        String name = node.getName().toString();
        if (FORBIDDEN_JAVA_IDENTIFIERS.contains(name)) {
            violations.add("forbidden identifier " + name);
        }
        return super.visitIdentifier(node, unused);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree node, Void unused) {
        String selected = node.toString();
        if (forbiddenQualifiedUse(selected)) {
            violations.add("forbidden qualified use " + selected);
        }
        if (FORBIDDEN_JAVA_IDENTIFIERS.contains(node.getIdentifier().toString())) {
            violations.add("forbidden member " + node.getIdentifier());
        }
        return super.visitMemberSelect(node, unused);
    }

    @Override
    public Void visitNewClass(NewClassTree node, Void unused) {
        String type = node.getIdentifier().toString();
        if (FORBIDDEN_JAVA_IDENTIFIERS.contains(type) || forbiddenQualifiedUse(type)) {
            violations.add("forbidden constructor " + type);
        }
        return super.visitNewClass(node, unused);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
        String select = node.getMethodSelect().toString();
        if (forbiddenMethodSelect(select) || forbiddenQualifiedUse(select) || select.endsWith(".getClass")) {
            violations.add("forbidden method call " + select);
        }
        return super.visitMethodInvocation(node, unused);
    }

    private static boolean forbiddenQualifiedUse(String value) {
        for (String prefix : FORBIDDEN_JAVA_QUALIFIED_PREFIXES) {
            if (value.startsWith(prefix) || value.contains("." + prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean forbiddenMethodSelect(String value) {
        for (String select : FORBIDDEN_JAVA_METHOD_SELECTS) {
            if (value.equals(select) || value.endsWith("." + select)) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> FORBIDDEN_JAVA_IDENTIFIERS = Set.of(
            "Runtime",
            "Process",
            "ProcessBuilder",
            "Class",
            "ClassLoader",
            "ServiceLoader",
            "Thread",
            "ThreadLocal",
            "Timer",
            "File",
            "Path",
            "Paths",
            "Files",
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
            "ScriptEngineManager",
            "Executor",
            "Executors",
            "CompletableFuture"
    );
    private static final Set<String> FORBIDDEN_JAVA_QUALIFIED_PREFIXES = Set.of(
            "java.io.",
            "java.nio.file.",
            "java.net.",
            "java.lang.reflect.",
            "java.lang.invoke.",
            "java.util.concurrent.",
            "javax.script.",
            "sun.",
            "jdk."
    );
    private static final Set<String> FORBIDDEN_JAVA_METHOD_SELECTS = Set.of(
            "System.exit",
            "System.getenv",
            "System.getProperty",
            "System.getProperties",
            "System.setProperty",
            "System.setProperties",
            "Runtime.getRuntime",
            "Class.forName",
            "Thread.sleep"
    );
}
