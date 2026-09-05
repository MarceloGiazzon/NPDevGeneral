package com.npdev.generator.emitters.pluginsource.policy;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * B30/SEC-9: AST walk over a mounted {@code plugin:java-controller} class that extracts the route
 * table an in-child dispatcher needs (httpMethod + full path + method name per route) and refuses
 * any method whose parameter shape cannot cross the process boundary declaratively.
 *
 * <p>Deliberately narrower than what {@code GeneratedPluginMountPlan.validateControllerRoutesWithinBasePath}
 * already accepts for basePath containment (a regex scan, kept unchanged): every route method must
 * carry a specific verb annotation ({@code @GetMapping}/{@code @PostMapping}/{@code @PutMapping}/
 * {@code @DeleteMapping}/{@code @PatchMapping}, never a bare {@code @RequestMapping} with a
 * {@code method=} attribute -- resolving an enum-array attribute is unneeded complexity when the
 * specific-verb annotations already cover every real case), and every parameter must be
 * {@code @PathVariable}, {@code @RequestParam}, or {@code @RequestBody} -- the only parameter kinds
 * the in-child dispatcher ({@code ManifestDrivenJavaControllerPluginHandler}, NPDevRuntimeHost) can
 * bind without a live Spring request context. A method using {@code HttpServletRequest},
 * {@code Authentication}, an injected service, or any other framework-coupled parameter is refused at
 * GENERATION time with a named violation, never silently accepted and failing at request time inside
 * the isolated child.
 */
public final class PluginControllerRouteVisitor extends TreeScanner<Void, Void> {

    private static final Set<String> VERB_MAPPING_ANNOTATIONS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping"
    );

    private static final Set<String> ALLOWED_PARAMETER_ANNOTATIONS = Set.of(
            "PathVariable", "RequestParam", "RequestBody"
    );

    private final List<String> violations;
    private final List<Route> routes = new ArrayList<>();
    private String classBasePath = "";

    public PluginControllerRouteVisitor(List<String> violations) {
        this.violations = violations;
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }

    @Override
    public Void visitClass(ClassTree node, Void unused) {
        for (AnnotationTree annotation : node.getModifiers().getAnnotations()) {
            if ("RequestMapping".equals(annotationSimpleName(annotation))) {
                List<String> paths = extractPaths(annotation);
                classBasePath = paths.isEmpty() ? "" : paths.get(0);
            }
        }
        return super.visitClass(node, unused);
    }

    @Override
    public Void visitMethod(MethodTree node, Void unused) {
        String httpMethod = null;
        List<String> methodPaths = List.of("");
        boolean requestMappingWithoutVerb = false;
        for (AnnotationTree annotation : node.getModifiers().getAnnotations()) {
            String name = annotationSimpleName(annotation);
            if (VERB_MAPPING_ANNOTATIONS.contains(name)) {
                httpMethod = verbFor(name);
                methodPaths = extractPaths(annotation);
            } else if ("RequestMapping".equals(name)) {
                requestMappingWithoutVerb = true;
            }
        }
        if (httpMethod != null) {
            for (String methodPath : methodPaths) {
                routes.add(new Route(httpMethod, joinPaths(classBasePath, methodPath), node.getName().toString()));
            }
            validateParameters(node);
        } else if (requestMappingWithoutVerb) {
            violations.add("method " + node.getName() + " uses @RequestMapping without a specific HTTP verb "
                    + "annotation -- use @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping so "
                    + "the isolated dispatcher can route by verb without resolving a method= attribute");
        }
        return super.visitMethod(node, unused);
    }

    private void validateParameters(MethodTree node) {
        for (VariableTree parameter : node.getParameters()) {
            boolean recognized = false;
            for (AnnotationTree annotation : parameter.getModifiers().getAnnotations()) {
                String annotationName = annotationSimpleName(annotation);
                if (!ALLOWED_PARAMETER_ANNOTATIONS.contains(annotationName)) {
                    continue;
                }
                recognized = true;
                // The in-child dispatcher (ManifestDrivenJavaControllerPluginHandler, NPDevRuntimeHost)
                // resolves @PathVariable/@RequestParam by NAME via reflection at invoke time -- relying
                // on Parameter.getName() would silently break for any generated app whose compiler
                // isn't invoked with -parameters (javac's default omits real parameter names). An
                // explicit name is required so binding never depends on that flag.
                if (!"RequestBody".equals(annotationName) && !hasExplicitName(annotation)) {
                    violations.add("method " + node.getName() + " parameter '" + parameter.getName()
                            + "' has a bare @" + annotationName + " with no explicit name -- write @"
                            + annotationName + "(\"" + parameter.getName() + "\") so the isolated dispatcher "
                            + "can bind it without relying on compiled-in parameter names");
                }
            }
            if (!recognized) {
                violations.add("method " + node.getName() + " parameter '" + parameter.getName()
                        + "' must be annotated @PathVariable, @RequestParam, or @RequestBody -- a "
                        + "plugin:java-controller method runs in an isolated child process with no live "
                        + "Spring request context, so only these declaratively-bindable parameter kinds "
                        + "are supported");
            }
        }
    }

    private static String verbFor(String annotationSimpleName) {
        return switch (annotationSimpleName) {
            case "GetMapping" -> "GET";
            case "PostMapping" -> "POST";
            case "PutMapping" -> "PUT";
            case "DeleteMapping" -> "DELETE";
            case "PatchMapping" -> "PATCH";
            default -> throw new IllegalStateException("Unmapped verb annotation: " + annotationSimpleName);
        };
    }

    private static String annotationSimpleName(AnnotationTree annotation) {
        Tree type = annotation.getAnnotationType();
        String text = type.toString();
        int lastDot = text.lastIndexOf('.');
        return lastDot < 0 ? text : text.substring(lastDot + 1);
    }

    /** {@code @PathVariable}/{@code @RequestParam} name -- positional {@code @PathVariable("id")} or
     *  named {@code value=}/{@code name=}, both real Spring aliases for the same attribute. */
    private static boolean hasExplicitName(AnnotationTree annotation) {
        List<? extends ExpressionTree> args = annotation.getArguments();
        for (ExpressionTree arg : args) {
            if (arg instanceof AssignmentTree assignment) {
                String attributeName = assignment.getVariable().toString();
                if ((attributeName.equals("value") || attributeName.equals("name"))
                        && !literalsFrom(assignment.getExpression()).stream().allMatch(String::isBlank)) {
                    return true;
                }
            } else if (!literalsFrom(arg).stream().allMatch(String::isBlank)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> extractPaths(AnnotationTree annotation) {
        List<? extends ExpressionTree> args = annotation.getArguments();
        if (args.isEmpty()) {
            return List.of("");
        }
        List<String> named = new ArrayList<>();
        List<String> positional = new ArrayList<>();
        for (ExpressionTree arg : args) {
            if (arg instanceof AssignmentTree assignment) {
                String attributeName = assignment.getVariable().toString();
                if (attributeName.equals("value") || attributeName.equals("path")) {
                    named.addAll(literalsFrom(assignment.getExpression()));
                }
            } else {
                positional.addAll(literalsFrom(arg));
            }
        }
        if (!named.isEmpty()) {
            return named;
        }
        if (!positional.isEmpty()) {
            return positional;
        }
        return List.of("");
    }

    private static List<String> literalsFrom(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String stringValue) {
            return List.of(stringValue);
        }
        if (expression instanceof NewArrayTree array && array.getInitializers() != null) {
            List<String> values = new ArrayList<>();
            for (ExpressionTree element : array.getInitializers()) {
                values.addAll(literalsFrom(element));
            }
            return values;
        }
        return List.of();
    }

    /** Mirrors {@code GeneratedPluginMountPlan.joinRoutePaths} -- duplicated rather than shared
     *  across the package boundary, since both sides are small and independently readable. */
    private static String joinPaths(String basePath, String subPath) {
        String base = basePath == null ? "" : basePath.trim();
        String sub = subPath == null ? "" : subPath.trim();
        if (sub.isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        String normalizedSub = sub.startsWith("/") ? sub : "/" + sub;
        if (base.isEmpty()) {
            return normalizedSub;
        }
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalizedBase + normalizedSub;
    }

    public record Route(String httpMethod, String path, String methodName) {
    }
}
