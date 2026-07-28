package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.emitters.trustedsource.model.ManifestEntry;
import com.npdev.generator.emitters.trustedsource.model.PanelAssets;
import com.npdev.generator.emitters.trustedsource.model.TrustedFlow;
import com.npdev.generator.emitters.trustedsource.model.TrustedPanel;
import com.npdev.generator.emitters.trustedsource.model.TrustedProcedure;
import com.npdev.generator.emitters.trustedsource.model.TrustedReference;
import com.npdev.generator.emitters.trustedsource.model.TrustedWidget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.npdev.generator.emitters.TrustedJavaSourcePolicy.validateJavaSource;
import static com.npdev.generator.emitters.TrustedPanelSourcePolicy.externalizePanelAssets;
import static com.npdev.generator.emitters.TrustedPanelSourcePolicy.validatePanelJavaScript;
import static com.npdev.generator.emitters.TrustedPanelSourcePolicy.validatePanelSource;
import static com.npdev.generator.emitters.TrustedSourceTemplateSupport.metadataText;

/**
 * Trusted-source manifest handling: turning a compiled model's procedure/panel/widget
 * references into {@link TrustedReference}s, reading and validating the sibling
 * {@code trusted-source-manifest.json}, hashing/loading/validating each referenced source file
 * into its model record, and (at the end of generation) writing the generated-side manifest.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedSourceManifest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private TrustedSourceManifest() {
    }

    static List<TrustedReference> referencesFrom(CompiledModel model) {
        List<TrustedReference> references = new ArrayList<>();
        if (model == null) {
            return references;
        }
        for (CompiledProcedure procedure : model.getProcedures()) {
            String entrypoint = metadataText(procedure.metadata(), "trustedSourceEntrypoint");
            if (!entrypoint.isBlank()) {
                references.add(new TrustedReference(
                        "procedure",
                        procedure.name(),
                        "",
                        entrypoint,
                        firstNonBlank(procedure.permissionRequirements()),
                        procedure
                ));
            }
        }
        for (CompiledPanel panel : model.getPanels()) {
            String entrypoint = metadataText(panel.metadata(), "trustedSourceEntrypoint");
            if (!entrypoint.isBlank()) {
                references.add(new TrustedReference(
                        "panel",
                        panel.name(),
                        panel.route(),
                        entrypoint,
                        requiredRoleFromVisibility(panel.visibility()),
                        panel
                ));
            }
        }
        Set<String> widgetPaths = new LinkedHashSet<>();
        for (CompiledConcept concept : model.getConcepts()) {
            for (CompiledField field : concept.getFields()) {
                CompiledPresentationMetadata ui = field.getUi();
                String customWidgetRef = ui == null ? null : ui.getCustomWidgetRef();
                if (customWidgetRef != null && !customWidgetRef.isBlank() && widgetPaths.add(customWidgetRef.trim())) {
                    references.add(new TrustedReference("widget", customWidgetRef.trim(), "", customWidgetRef.trim(), "", null));
                }
            }
        }
        return references;
    }

    static List<TrustedFlow> trustedFlowsFrom(CompiledModel model) {
        List<TrustedFlow> flows = new ArrayList<>();
        if (model == null) {
            return flows;
        }
        for (CompiledFlow flow : model.getFlows()) {
            if (flow != null && flow.isStartEndpoint()) {
                flows.add(new TrustedFlow(
                        flow.getName(),
                        firstGeneratedActionName(flow)
                ));
            }
        }
        return flows;
    }

    private static String firstGeneratedActionName(CompiledFlow flow) {
        if (flow == null) {
            return "";
        }
        for (com.npdev.dsl.v1.compiled.CompiledFlowStep step : flow.getSteps()) {
            String actionName = step.getGeneratedActionName();
            if (actionName != null && !actionName.isBlank()) {
                return actionName.trim();
            }
        }
        return "";
    }

    static List<ManifestEntry> readManifest(Path manifestPath, Path sourceRoot) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(manifestPath.toFile());
        if (!"npdev-trusted-source-manifest.v1".equals(root.path("schemaVersion").asText())) {
            throw new IllegalStateException("Unsupported trusted source manifest schemaVersion.");
        }
        if (!root.path("entries").isArray()) {
            throw new IllegalStateException("Trusted source manifest entries must be an array.");
        }
        List<ManifestEntry> entries = new ArrayList<>();
        for (JsonNode node : root.path("entries")) {
            ManifestEntry entry = new ManifestEntry(
                    text(node, "entryId"),
                    text(node, "kind"),
                    text(node, "relativePath"),
                    text(node, "language"),
                    text(node, "sha256"),
                    text(node, "runtimeBinding"),
                    text(node, "className"),
                    firstNonBlank(text(node, "method"), "execute"),
                    text(node, "requiredRole"),
                    node.path("tenantScoped").asBoolean(false)
            );
            validateManifestEntry(sourceRoot, entry);
            entries.add(entry);
        }
        return entries;
    }

    private static void validateManifestEntry(Path sourceRoot, ManifestEntry entry) {
        if (!Set.of("procedure", "panel", "widget").contains(entry.kind())) {
            throw new IllegalStateException("Unsupported trusted source kind: " + entry.kind());
        }
        if (!isSafeRelativePath(entry.relativePath())) {
            throw new IllegalStateException("Unsafe trusted source relative path: " + entry.relativePath());
        }
        if (!entry.sha256().matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Trusted source manifest entry has invalid SHA-256: " + entry.relativePath());
        }
        Path source = sourceRoot.resolve(entry.relativePath()).normalize();
        if (!source.startsWith(sourceRoot) || !Files.isRegularFile(source)) {
            throw new IllegalStateException("Trusted source file is missing or outside the model directory: " + entry.relativePath());
        }
        if ("procedure".equals(entry.kind())) {
            if (!isJavaIdentifier(entry.className()) || !isJavaIdentifier(entry.method())) {
                throw new IllegalStateException("Trusted procedure className/method must be Java identifiers: " + entry.relativePath());
            }
            if (!"java".equals(entry.language())) {
                throw new IllegalStateException("Trusted procedure language must be java: " + entry.relativePath());
            }
        }
        if ("panel".equals(entry.kind()) && !entry.runtimeBinding().startsWith("panel:")) {
            throw new IllegalStateException("Trusted panel runtimeBinding must use panel:<route>: " + entry.relativePath());
        }
        if ("widget".equals(entry.kind()) && !"javascript".equals(entry.language())) {
            throw new IllegalStateException("Trusted widget language must be javascript: " + entry.relativePath());
        }
        // A widget is a shared script embedded in whatever form renders it, not a routed page of
        // its own -- it has no independent access-control role to require, unlike a procedure/panel.
        if (!"widget".equals(entry.kind()) && entry.requiredRole().isBlank()) {
            throw new IllegalStateException("Trusted source entry requiredRole is required: " + entry.relativePath());
        }
    }

    static TrustedProcedure toProcedure(TrustedReference reference, ManifestEntry entry, Path sourceRoot) throws IOException {
        Path sourcePath = sourceRoot.resolve(entry.relativePath()).normalize();
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        validateJavaSource(source, entry.relativePath());
        return new TrustedProcedure(
                reference.id(),
                entry.relativePath(),
                entry.className(),
                entry.method(),
                normalizeRole(firstNonBlank(entry.requiredRole(), reference.requiredRole())),
                entry.tenantScoped(),
                reference.source() instanceof CompiledProcedure compiledProcedure
                        ? compiledProcedure.actionDescriptor()
                        : null,
                reference.source() instanceof CompiledProcedure compiledProcedure
                        ? compiledProcedure.metadata()
                        : Map.of(),
                source
        );
    }

    static TrustedPanel toPanel(TrustedReference reference, ManifestEntry entry, Path sourceRoot) throws IOException {
        Path sourcePath = sourceRoot.resolve(entry.relativePath()).normalize();
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        validatePanelSource(source, entry.relativePath());
        String route = firstNonBlank(reference.route(), entry.runtimeBinding().substring("panel:".length()));
        if (!route.startsWith("/")) {
            throw new IllegalStateException("Trusted panel route must start with /: " + route);
        }
        String resourcePrefix = safeResourceName(reference.id());
        PanelAssets assets = externalizePanelAssets(source, resourcePrefix);
        return new TrustedPanel(
                reference.id(),
                route,
                entry.relativePath(),
                resourcePrefix + ".html",
                resourcePrefix + ".css",
                resourcePrefix + ".js",
                normalizeRole(firstNonBlank(entry.requiredRole(), reference.requiredRole())),
                entry.tenantScoped(),
                assets.html(),
                assets.css(),
                assets.js()
        );
    }

    static TrustedWidget toWidget(ManifestEntry entry, Path sourceRoot) throws IOException {
        Path sourcePath = sourceRoot.resolve(entry.relativePath()).normalize();
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
        validatePanelJavaScript(source, entry.relativePath());
        return new TrustedWidget(entry.relativePath(), source);
    }

    static void validateHash(Path sourceRoot, ManifestEntry entry) throws IOException {
        String actual = sha256(sourceRoot.resolve(entry.relativePath()).normalize());
        if (!actual.equals(entry.sha256())) {
            throw new IllegalStateException("Trusted source SHA-256 mismatch for " + entry.relativePath());
        }
    }

    static String generationManifest(
            List<ManifestEntry> entries,
            List<TrustedProcedure> procedures,
            List<TrustedPanel> panels,
            List<TrustedWidget> widgets,
            Path manifestPath
    ) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "npdev-generated-trusted-source-manifest.v1");
        output.put("manifestPath", manifestPath.toString());
        output.put("overlayHarnessUsed", false);
        output.put("procedureCount", procedures.size());
        output.put("panelCount", panels.size());
        output.put("widgetCount", widgets.size());
        output.put("entries", entries);
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
    }

    private static String requiredRoleFromVisibility(String visibility) {
        String value = visibility == null ? "" : visibility.trim();
        if (value.toLowerCase(Locale.ROOT).startsWith("role:")) {
            return value.substring("role:".length()).trim();
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String firstNonBlank(List<String> values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeRole(String role) {
        String normalized = firstNonBlank(role);
        return normalized.isBlank() ? "ADMIN" : normalized.toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static boolean isSafeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = relativePath.replace('\\', '/');
        return !normalized.startsWith("/")
                && !normalized.matches("^[A-Za-z]:.*")
                && !normalized.contains("://")
                && !normalized.contains("//")
                && !normalized.contains("../")
                && !normalized.equals("..")
                && !normalized.startsWith("../");
    }

    private static boolean isJavaIdentifier(String value) {
        return value != null && JAVA_IDENTIFIER.matcher(value).matches();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            throw new IOException("Failed to hash trusted source: " + path, e);
        }
    }

    private static String safeResourceName(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "trusted-panel" : normalized;
    }
}
