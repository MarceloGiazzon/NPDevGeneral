package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The small, stable set of real controller route PATTERNS the invocations catalog's generator
 * methods ({@link CompiledMetadataCanonicalJson#toJson}) are built from (docs/FRONTEND_STRATEGY_PLAN.md,
 * docs/NEXT_EXECUTION_PLAN.md P4.2) -- one or two per controller, {@code {*}} standing in for a
 * Spring path variable of any name.
 *
 * <p>Shared by two independent checks so this list is never hand-maintained twice
 * (docs/REMEDIATION_PLAN.md R-R1):
 * <ul>
 *   <li>{@code InvocationCatalogRouteConformanceTest} (this module) -- every emitted invocation path
 *       must match one of these patterns, i.e. the catalog cannot claim a route that doesn't exist.</li>
 *   <li>{@code RoutePatternStalenessTest} (generator module) -- every pattern here must match at
 *       least one route actually extracted from a freshly generated app's controllers, i.e. this
 *       list cannot go stale after a real controller's route changes underneath it.</li>
 * </ul>
 *
 * <p>Each pattern was independently verified against the REAL generated source of a real app
 * (WmsOffice, 32 concepts / 15 flows / panels / 2 aggregates, regenerated 2026-07-28) -- not assumed
 * from a template or the model's shape (see the invocations-catalog emitter's own javadoc history:
 * an earlier design sketch got several of these paths wrong, e.g. assuming
 * {@code /api/concepts/&lt;ConceptName&gt;} when the real generic-CRUD path is
 * {@code /api/concepts/&lt;tableName&gt;}).
 */
public final class RealRoutePatterns {

    public record Entry(String method, String pathPattern, Pattern regex) {
    }

    public static final List<Entry> ALL = List.of(
            // GeneratedConceptCrudController (generated tree) -- path variable is the TABLE name.
            entry("GET", "/api/concepts/{*}"),
            entry("POST", "/api/concepts/{*}"),
            entry("GET", "/api/concepts/{*}/{*}"),
            entry("PUT", "/api/concepts/{*}/{*}"),
            entry("DELETE", "/api/concepts/{*}/{*}"),
            // ConceptQueryController (template tree, @RequestMapping({"/api/v1/concepts","/api/concepts"}))
            // -- path variable is the concept NAME, a different vocabulary at the same nominal depth.
            entry("GET", "/api/v1/concepts/{*}/page"),
            entry("GET", "/api/concepts/{*}/page"),
            entry("GET", "/api/v1/concepts/{*}/export.csv"),
            entry("GET", "/api/concepts/{*}/export.csv"),
            // npdev-runtime-flow-controller.mustache (generated tree, class @RequestMapping("/api")).
            entry("POST", "/api/v1/flows/{*}/execute"),
            entry("POST", "/api/flows/{*}/execute"),
            // DirectExecutionGatewayController (template tree) -- flow-bound panel actions only.
            entry("POST", "/api/v1/execute/panel-action"),
            entry("POST", "/api/execute/panel-action"),
            // RuntimeUiMetadataController (template tree, @RequestMapping({"/api/v1/runtime/metadata/ui","/api/runtime/metadata/ui"})).
            entry("POST", "/api/v1/runtime/metadata/ui/panels/{*}/actions/{*}"),
            entry("POST", "/api/runtime/metadata/ui/panels/{*}/actions/{*}"),
            entry("POST", "/api/v1/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows"),
            entry("POST", "/api/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows"),
            entry("DELETE", "/api/v1/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows/{*}"),
            entry("DELETE", "/api/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows/{*}"),
            // AggregateApiController (template tree, @RequestMapping({"/api/v1/runtime/aggregate","/api/runtime/aggregate"})).
            entry("GET", "/api/v1/runtime/aggregate/{*}/{*}"),
            entry("GET", "/api/runtime/aggregate/{*}/{*}"),
            entry("POST", "/api/v1/runtime/aggregate/{*}"),
            entry("POST", "/api/runtime/aggregate/{*}"),
            entry("POST", "/api/v1/runtime/aggregate/{*}/invoke/{*}"),
            entry("POST", "/api/runtime/aggregate/{*}/invoke/{*}"),
            // FileUploadController (template tree, @RequestMapping("/api/files")) -- concept resolved
            // by NAME (requireFileField, case-insensitive), a third vocabulary at this same depth.
            entry("POST", "/api/files/{*}/{*}")
    );

    private static Entry entry(String method, String pathPattern) {
        String[] segments = pathPattern.split("/");
        StringBuilder regex = new StringBuilder("^").append(Pattern.quote(method)).append(" ");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append("/");
            }
            regex.append("{*}".equals(segments[i]) ? "[^/]+" : Pattern.quote(segments[i]));
        }
        return new Entry(method, pathPattern, Pattern.compile(regex.append("$").toString()));
    }

    public static boolean isRealRoute(String method, String path) {
        String candidate = method + " " + path;
        for (Entry e : ALL) {
            if (e.regex().matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    private RealRoutePatterns() {
    }
}
