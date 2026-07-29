package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.RealRoutePatterns;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/REMEDIATION_PLAN.md R-R1: {@code InvocationCatalogRouteConformanceTest} (DSL module) proves
 * every invocations-catalog path matches one of {@link RealRoutePatterns}' entries -- but that test
 * only ever checks the CATALOG against the patterns, never the patterns against reality. If a real
 * controller's route changes, the hand-maintained pattern goes stale silently: nothing detects a
 * pattern with zero matching real routes.
 *
 * <p>This test closes that gap from the one module that can (the DSL module cannot see a generated
 * app). It builds the real route population two ways:
 * <ul>
 *   <li>the "generated tree" (model-specific): a real generation pass against
 *       {@code medium-expense-approval} (declares both a concept and a flow, exercising
 *       {@code business-concept-crud-controller.mustache} and
 *       {@code npdev-runtime-flow-controller.mustache}), scanning every emitted {@code .java} file;</li>
 *   <li>the "template tree" (NOT model-specific -- copied verbatim into every generated app, so no
 *       generation is needed): read directly from this sibling module,
 *       {@code NPDevRuntimeHost/src/main/java/com/finalexec/api/*.java}.</li>
 * </ul>
 * Every {@link RealRoutePatterns#ALL} entry must match at least one extracted route. A pattern with
 * zero matches means the controller it was written against has moved on without it.
 */
class RoutePatternStalenessTest {

    /** One {@code @RequestMapping(...)} immediately followed (only whitespace/newline between) by a
     * {@code class} declaration -- the CLASS-level base path. Method-level mappings in this repo's
     * controllers never use bare {@code @RequestMapping} (always the Get/Post/Put/Delete/PatchMapping
     * aliases, matched separately below), so this single check cannot mistake a method mapping for
     * the class one. */
    private static final Pattern CLASS_BASE_PATH = Pattern.compile(
            "@RequestMapping\\(([^)]*)\\)\\s*\\r?\\n\\s*(?:public\\s+)?(?:final\\s+)?class\\s+\\w+");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\s*(\\([^)]*\\))?");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");
    private static final Map<String, String> HTTP_METHOD_BY_ANNOTATION = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH"
    );

    private record RealRoute(String method, String path) {
    }

    @Test
    void everyRealRoutePatternMatchesARouteExtractedFromRealControllers() throws Exception {
        List<RealRoute> realRoutes = new ArrayList<>();
        realRoutes.addAll(extractRoutesFromGeneratedTree());
        realRoutes.addAll(extractRoutesFromTemplateTree());

        assertFalse(realRoutes.isEmpty(), "Expected to extract at least one real route from either tree.");

        List<String> stale = new ArrayList<>();
        for (RealRoutePatterns.Entry pattern : RealRoutePatterns.ALL) {
            boolean matched = realRoutes.stream()
                    .anyMatch(route -> pattern.regex().matcher(route.method() + " " + route.path()).matches());
            if (!matched) {
                stale.add(pattern.method() + " " + pattern.pathPattern());
            }
        }
        assertTrue(stale.isEmpty(),
                () -> "RealRoutePatterns entries with no matching real controller route (stale -- the "
                        + "controller they were written against has moved on -- or the extraction "
                        + "heuristic itself needs updating for a new annotation shape): " + stale);
    }

    private List<RealRoute> extractRoutesFromGeneratedTree() throws Exception {
        Path model = resolvePath(List.of(
                Path.of("..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json"),
                Path.of("..", "..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json")
        ));

        ModelAst ast = new JsonModelParser().parse(model);
        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-route-staleness-generation-");
        Path migrations = Files.createTempDirectory("npdev-route-staleness-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path generatedJavaRoot = out.resolve("src/main/java");
        List<RealRoute> routes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(generatedJavaRoot)) {
            for (Path javaFile : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                routes.addAll(extractRoutesFromSource(Files.readString(javaFile)));
            }
        }
        return routes;
    }

    /** Not model-specific -- copied verbatim into every generated app -- so read directly rather than
     * generating anything. */
    private List<RealRoute> extractRoutesFromTemplateTree() throws IOException {
        Path controllersDir = resolvePath(List.of(
                Path.of("..", "..", "NPDevRuntimeHost", "src", "main", "java", "com", "finalexec", "api"),
                Path.of("..", "..", "..", "NPDevRuntimeHost", "src", "main", "java", "com", "finalexec", "api")
        ));

        List<RealRoute> routes = new ArrayList<>();
        try (Stream<Path> files = Files.list(controllersDir)) {
            for (Path javaFile : files.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                routes.addAll(extractRoutesFromSource(Files.readString(javaFile)));
            }
        }
        return routes;
    }

    private List<RealRoute> extractRoutesFromSource(String source) {
        List<String> basePaths = extractBasePaths(source);
        List<RealRoute> routes = new ArrayList<>();
        Matcher methodMatcher = METHOD_MAPPING.matcher(source);
        while (methodMatcher.find()) {
            String httpMethod = HTTP_METHOD_BY_ANNOTATION.get(methodMatcher.group(1));
            String args = methodMatcher.group(2);
            List<String> values = args == null ? List.of() : extractStringLiterals(args);
            if (values.isEmpty()) {
                values = List.of(""); // bare @GetMapping etc. -- just the class base path
            }
            for (String base : basePaths) {
                for (String value : values) {
                    routes.add(new RealRoute(httpMethod, base + value));
                }
            }
        }
        return routes;
    }

    private List<String> extractBasePaths(String source) {
        Matcher matcher = CLASS_BASE_PATH.matcher(source);
        if (matcher.find()) {
            List<String> literals = extractStringLiterals(matcher.group(1));
            if (!literals.isEmpty()) {
                return literals;
            }
        }
        return List.of(""); // no class-level @RequestMapping -- method paths are absolute
    }

    private List<String> extractStringLiterals(String text) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(text);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve any candidate path: " + candidates);
    }
}
