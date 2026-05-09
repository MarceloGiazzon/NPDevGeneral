package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class TrustedSourceEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PACKAGE_NAME = "com.npdev.generated.trusted";
    private static final String PACKAGE_PATH = "com/npdev/generated/trusted";
    private static final String FULL_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; form-action 'self'; object-src 'none'; base-uri 'self'";
    private static final Set<String> ALLOWED_JAVA_IMPORTS = Set.of(
            "java.util.List",
            "java.util.Map",
            "java.util.Set",
            "java.util.Optional",
            "java.math.BigDecimal",
            "java.util.UUID",
            "java.time.Instant"
    );
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private final GeneratedSourceWriter writer;

    public TrustedSourceEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(CompiledModel model, Path modelSourcePath) throws IOException {
        List<TrustedReference> references = referencesFrom(model);
        if (references.isEmpty()) {
            return;
        }
        if (modelSourcePath == null || modelSourcePath.getParent() == null) {
            throw new IllegalStateException("Trusted source references require a model source path for sibling manifest discovery.");
        }

        Path sourceRoot = modelSourcePath.toAbsolutePath().normalize().getParent();
        Path manifestPath = sourceRoot.resolve("trusted-source-manifest.json").normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Trusted source references require trusted-source-manifest.json next to the model.");
        }

        List<ManifestEntry> entries = readManifest(manifestPath, sourceRoot);
        Map<String, TrustedReference> referenceByKey = new LinkedHashMap<>();
        for (TrustedReference reference : references) {
            referenceByKey.put(key(reference.kind(), reference.relativePath()), reference);
        }

        Map<String, ManifestEntry> entryByKey = new LinkedHashMap<>();
        for (ManifestEntry entry : entries) {
            String key = key(entry.kind(), entry.relativePath());
            if (entryByKey.put(key, entry) != null) {
                throw new IllegalStateException("Duplicate trusted source manifest entry: " + key);
            }
            if (!referenceByKey.containsKey(key)) {
                throw new IllegalStateException("Unexpected trusted source manifest entry with no model reference: " + entry.relativePath());
            }
        }
        for (TrustedReference reference : references) {
            if (!entryByKey.containsKey(key(reference.kind(), reference.relativePath()))) {
                throw new IllegalStateException("Trusted source model reference has no manifest entry: " + reference.relativePath());
            }
        }

        List<TrustedProcedure> procedures = new ArrayList<>();
        List<TrustedPanel> panels = new ArrayList<>();
        for (TrustedReference reference : references) {
            ManifestEntry entry = entryByKey.get(key(reference.kind(), reference.relativePath()));
            validateHash(sourceRoot, entry);
            if ("procedure".equals(reference.kind())) {
                procedures.add(toProcedure(reference, entry, sourceRoot));
            } else if ("panel".equals(reference.kind())) {
                panels.add(toPanel(reference, entry, sourceRoot));
            }
        }

        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/NPDevProcedureContext.java",
                procedureContextSource()
        );
        for (TrustedProcedure procedure : procedures) {
            writer.writeRelative(
                    "src/main/java/" + PACKAGE_PATH + "/" + procedure.className() + ".java",
                    packagedProcedureSource(procedure)
            );
        }
        for (TrustedPanel panel : panels) {
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.resourceName(),
                    panel.source()
            );
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.cssResourceName(),
                    panel.cssSource()
            );
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.jsResourceName(),
                    panel.jsSource()
            );
        }
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedTrustedSourceRuntimeController.java",
                controllerSource(procedures, panels)
        );
        writer.writeRelative(
                "src/main/resources/trusted-source/trusted-source-generation-manifest.json",
                generationManifest(entries, procedures, panels, manifestPath)
        );
    }

    private static List<TrustedReference> referencesFrom(CompiledModel model) {
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
        return references;
    }

    private static List<ManifestEntry> readManifest(Path manifestPath, Path sourceRoot) throws IOException {
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
        if (!Set.of("procedure", "panel").contains(entry.kind())) {
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
        if (entry.requiredRole().isBlank()) {
            throw new IllegalStateException("Trusted source entry requiredRole is required: " + entry.relativePath());
        }
    }

    private static TrustedProcedure toProcedure(TrustedReference reference, ManifestEntry entry, Path sourceRoot) throws IOException {
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
                source
        );
    }

    private static TrustedPanel toPanel(TrustedReference reference, ManifestEntry entry, Path sourceRoot) throws IOException {
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

    private static void validateHash(Path sourceRoot, ManifestEntry entry) throws IOException {
        String actual = sha256(sourceRoot.resolve(entry.relativePath()).normalize());
        if (!actual.equals(entry.sha256())) {
            throw new IllegalStateException("Trusted source SHA-256 mismatch for " + entry.relativePath());
        }
    }

    private static void validateJavaSource(String source, String relativePath) {
        if (source.matches("(?s).*\\bpackage\\s+[A-Za-z0-9_.]+\\s*;.*")) {
            throw new IllegalStateException("Trusted Java source must not declare a package: " + relativePath);
        }
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("java.io import", "(?m)^\\s*import\\s+(static\\s+)?java\\.io\\."),
                Map.entry("java.nio.file import", "(?m)^\\s*import\\s+(static\\s+)?java\\.nio\\.file\\."),
                Map.entry("java.net import", "(?m)^\\s*import\\s+(static\\s+)?java\\.net\\."),
                Map.entry("Runtime", "\\b(java\\.lang\\.)?Runtime\\b|Runtime\\.getRuntime\\s*\\("),
                Map.entry("Process", "\\b(java\\.lang\\.)?Process\\b"),
                Map.entry("ProcessBuilder", "\\b(java\\.lang\\.)?ProcessBuilder\\b|new\\s+ProcessBuilder\\s*\\("),
                Map.entry("System.getenv", "System\\.getenv\\s*\\("),
                Map.entry("System.getProperty", "System\\.getProperty\\s*\\("),
                Map.entry("System.getProperties", "System\\.getProperties\\s*\\("),
                Map.entry("System.setProperty", "System\\.setProperty\\s*\\("),
                Map.entry("System.exit", "System\\.exit\\s*\\("),
                Map.entry("reflection import", "(?m)^\\s*import\\s+(static\\s+)?java\\.lang\\.reflect\\."),
                Map.entry("Class type", "\\b(java\\.lang\\.)?Class\\b|\\bClass\\.forName\\s*\\("),
                Map.entry("ClassLoader", "\\bClassLoader\\b"),
                Map.entry("ServiceLoader", "\\bServiceLoader\\b|(?m)^\\s*import\\s+(static\\s+)?java\\.util\\.ServiceLoader\\b"),
                Map.entry("Thread", "\\bThread\\b|new\\s+Thread\\s*\\("),
                Map.entry("ThreadLocal", "\\bThreadLocal\\b"),
                Map.entry("Timer", "\\bTimer\\b|(?m)^\\s*import\\s+(static\\s+)?java\\.util\\.Timer\\b"),
                Map.entry("concurrency import", "(?m)^\\s*import\\s+(static\\s+)?java\\.util\\.concurrent\\."),
                Map.entry("javax.script import", "(?m)^\\s*import\\s+(static\\s+)?javax\\.script\\."),
                Map.entry("sun import", "(?m)^\\s*import\\s+(static\\s+)?sun\\."),
                Map.entry("jdk import", "(?m)^\\s*import\\s+(static\\s+)?jdk\\."),
                Map.entry("static initializer", "\\bstatic\\s*\\{"),
                Map.entry("native method", "\\bnative\\b")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(source).find()) {
                throw new IllegalStateException("Forbidden Java source use in " + relativePath + ": " + pattern.getKey());
            }
        }
        var matcher = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z0-9_.*]+)\\s*;").matcher(source);
        while (matcher.find()) {
            String importName = matcher.group(1);
            if (importName.endsWith(".*") || !ALLOWED_JAVA_IMPORTS.contains(importName)) {
                throw new IllegalStateException("Import is not allowlisted in " + relativePath + ": " + importName);
            }
        }
    }

    private static void validatePanelSource(String source, String relativePath) {
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("external script/style/image/form URL", "(?i)\\b(src|href|action)\\s*=\\s*['\"]\\s*(https?:)?//"),
                Map.entry("iframe/object/embed/base", "(?i)<\\s*(iframe|object|embed|base)\\b"),
                Map.entry("css import", "(?i)@import\\s+"),
                Map.entry("css url", "(?i)url\\s*\\(\\s*['\"]?\\s*(https?:)?//"),
                Map.entry("external fetch URL", "(?i)\\bfetch\\s*\\(\\s*['\"]\\s*(https?:)?//"),
                Map.entry("non-generated same-origin fetch", "(?i)\\bfetch\\s*\\(\\s*['\"]/(?!generated/)"),
                Map.entry("websocket URL", "(?i)\\bnew\\s+WebSocket\\s*\\(\\s*['\"]\\s*(wss?:)?//"),
                Map.entry("eval", "\\beval\\s*\\("),
                Map.entry("Function constructor", "\\bnew\\s+Function\\s*\\("),
                Map.entry("dynamic import", "\\bimport\\s*\\("),
                Map.entry("inline event handler", "(?i)\\son[a-z]+\\s*="),
                Map.entry("javascript URL", "(?i)javascript:")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(source).find()) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": " + pattern.getKey());
            }
        }
    }

    private static PanelAssets externalizePanelAssets(String source, String resourcePrefix) {
        StringBuilder css = new StringBuilder();
        StringBuilder js = new StringBuilder();
        Pattern stylePattern = Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");
        var styleMatcher = stylePattern.matcher(source);
        String withoutStyles = styleMatcher.replaceAll(match -> {
            css.append(match.group(1)).append("\n");
            return "";
        });
        Pattern inlineScriptPattern = Pattern.compile("(?is)<script(?![^>]*\\bsrc\\s*=)[^>]*>(.*?)</script>");
        var scriptMatcher = inlineScriptPattern.matcher(withoutStyles);
        String html = scriptMatcher.replaceAll(match -> {
            js.append(match.group(1)).append("\n");
            return "";
        });
        String cssLink = "<link rel=\"stylesheet\" href=\"/generated/trusted-source/panel/" + resourcePrefix + ".css\">";
        String jsScript = "<script src=\"/generated/trusted-source/panel/" + resourcePrefix + ".js\"></script>";
        if (html.toLowerCase(Locale.ROOT).contains("</head>")) {
            html = html.replaceFirst("(?i)</head>", cssLink + "\n</head>");
        } else {
            html = cssLink + "\n" + html;
        }
        if (html.toLowerCase(Locale.ROOT).contains("</body>")) {
            html = html.replaceFirst("(?i)</body>", jsScript + "\n</body>");
        } else {
            html = html + "\n" + jsScript;
        }
        if (Pattern.compile("(?is)<style\\b|<script(?![^>]*\\bsrc\\s*=)").matcher(html).find()) {
            throw new IllegalStateException("Trusted panel externalization left inline style/script in generated HTML.");
        }
        return new PanelAssets(html, css.toString(), js.toString());
    }

    private static String procedureContextSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.List;
                import java.util.Map;

                public interface NPDevProcedureContext {
                    String tenantId();
                    String actorId();
                    List<Map<String, Object>> saveMany(String concept, List<Map<String, Object>> records);
                }
                """;
    }

    private static String packagedProcedureSource(TrustedProcedure procedure) {
        return "package " + PACKAGE_NAME + ";\n\n" + procedure.source();
    }

    private static String controllerSource(List<TrustedProcedure> procedures, List<TrustedPanel> panels) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.RuntimeContextService;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import jakarta.servlet.http.HttpServletRequest;
                import org.springframework.core.io.ClassPathResource;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.MediaType;
                import org.springframework.http.ResponseEntity;
                import org.springframework.util.StreamUtils;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                import java.nio.charset.StandardCharsets;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                @RestController
                public class GeneratedTrustedSourceRuntimeController {
                    public static final String FULL_CSP = """).append(quote(FULL_CSP)).append("""
                ;
                    private final RuntimeContextService runtimeContextService;
                    private final ConceptGateway conceptGateway;

                    public GeneratedTrustedSourceRuntimeController(
                            RuntimeContextService runtimeContextService,
                            ConceptGateway conceptGateway
                    ) {
                        this.runtimeContextService = runtimeContextService;
                        this.conceptGateway = conceptGateway;
                    }

                    @PostMapping(value = "/generated/procedures/{procedureName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> invokeProcedure(
                            @PathVariable String procedureName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        Map<String, Object> input = body == null ? Map.of() : body;
                        int before = runtimeCount(context, "User");
                """);

        for (TrustedProcedure procedure : procedures) {
            source.append("        if (").append(quote(procedure.id())).append(".equals(procedureName)) {\n")
                    .append("            return invoke").append(methodSuffix(procedure.id())).append("(context, input, before);\n")
                    .append("        }\n");
        }
        source.append("""
                        Map<String, Object> missing = new LinkedHashMap<>();
                        missing.put("status", "rejected");
                        missing.put("reason", "unknown-procedure");
                        missing.put("sideEffectCountBefore", before);
                        missing.put("sideEffectCountAfter", runtimeCount(context, "User"));
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(missing);
                    }

                """);

        for (TrustedProcedure procedure : procedures) {
            source.append("    private ResponseEntity<Map<String, Object>> invoke").append(methodSuffix(procedure.id()))
                    .append("(ExecutionContext context, Map<String, Object> input, int before) {\n")
                    .append("        ResponseEntity<Map<String, Object>> rejection = rejectIfUnauthorized(context, input, ")
                    .append(quote(procedure.requiredRole())).append(", ").append(procedure.tenantScoped()).append(", before);\n")
                    .append("        if (rejection != null) {\n")
                    .append("            return rejection;\n")
                    .append("        }\n")
                    .append("        GeneratedTrustedProcedureContext trustedContext = new GeneratedTrustedProcedureContext(context);\n")
                    .append("        Map<String, Object> result = new ").append(procedure.className()).append("().")
                    .append(procedure.method()).append("(trustedContext);\n")
                    .append("        Map<String, Object> response = new LinkedHashMap<>();\n")
                    .append("        response.put(\"status\", \"ok\");\n")
                    .append("        response.put(\"procedureName\", ").append(quote(procedure.id())).append(");\n")
                    .append("        response.put(\"tenantId\", context.tenantId());\n")
                    .append("        response.put(\"sideEffectCountBefore\", before);\n")
                    .append("        response.put(\"sideEffectCountAfter\", runtimeCount(context, \"User\"));\n")
                    .append("        if (result != null) {\n")
                    .append("            response.putAll(result);\n")
                    .append("        }\n")
                    .append("        return ResponseEntity.ok(response);\n")
                    .append("    }\n\n");
        }

        for (TrustedPanel panel : panels) {
            source.append("    @GetMapping(value = ").append(quote(panel.route())).append(", produces = MediaType.TEXT_HTML_VALUE)\n")
                    .append("    public ResponseEntity<String> panel").append(methodSuffix(panel.id())).append("(HttpServletRequest request) throws Exception {\n")
                    .append("        ExecutionContext context = runtimeContextService.currentContext(request);\n")
                    .append("        int before = runtimeCount(context, \"User\");\n")
                    .append("        ResponseEntity<Map<String, Object>> rejection = rejectIfUnauthorized(context, Map.of(), ")
                    .append(quote(panel.requiredRole())).append(", false, before);\n")
                    .append("        if (rejection != null) {\n")
                    .append("            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(\"\");\n")
                    .append("        }\n")
                    .append("        String html = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.resourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        String bridge = \"<script src=\\\"/generated/trusted-source/npdev-panel-runtime.js\\\"></script>\";\n")
                    .append("        if (html.contains(\"</head>\")) {\n")
                    .append("            html = html.replace(\"</head>\", bridge + \"</head>\");\n")
                    .append("        } else {\n")
                    .append("            html = bridge + html;\n")
                    .append("        }\n")
                    .append("        return ResponseEntity.ok().header(\"Content-Security-Policy\", FULL_CSP).contentType(MediaType.TEXT_HTML).body(html);\n")
                    .append("    }\n\n");
            source.append("    @GetMapping(value = ").append(quote("/generated/trusted-source/panel/" + panel.cssResourceName())).append(", produces = \"text/css\")\n")
                    .append("    public ResponseEntity<String> panelCss").append(methodSuffix(panel.id())).append("() throws Exception {\n")
                    .append("        String css = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.cssResourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        return ResponseEntity.ok().contentType(MediaType.valueOf(\"text/css\")).body(css);\n")
                    .append("    }\n\n");
            source.append("    @GetMapping(value = ").append(quote("/generated/trusted-source/panel/" + panel.jsResourceName())).append(", produces = \"application/javascript\")\n")
                    .append("    public ResponseEntity<String> panelJs").append(methodSuffix(panel.id())).append("() throws Exception {\n")
                    .append("        String js = StreamUtils.copyToString(new ClassPathResource(")
                    .append(quote("trusted-source/panel/" + panel.jsResourceName()))
                    .append(").getInputStream(), StandardCharsets.UTF_8);\n")
                    .append("        return ResponseEntity.ok().contentType(MediaType.valueOf(\"application/javascript\")).body(js);\n")
                    .append("    }\n\n");
        }

        source.append("""
                    @GetMapping(value = "/generated/trusted-source/npdev-panel-runtime.js", produces = "application/javascript")
                    public ResponseEntity<String> panelRuntimeBridge() {
                        String js = \"""
                window.NPDev = window.NPDev || {};
                window.NPDev.callProcedure = async function(name, payload) {
                  const headers = { 'Content-Type': 'application/json' };
                  const apiKey = window.NPDevApiKey || (window.localStorage && window.localStorage.getItem('npdev.apiKey')) || '';
                  if (apiKey) {
                    headers['X-Api-Key'] = apiKey;
                  }
                  const response = await fetch('/generated/procedures/' + encodeURIComponent(name), {
                    method: 'POST',
                    headers,
                    body: JSON.stringify(payload || {})
                  });
                  const body = await response.json();
                  if (!response.ok) {
                    throw new Error(body.reason || body.error || 'trusted procedure failed');
                  }
                  return body;
                };
                \""";
                        return ResponseEntity.ok().contentType(MediaType.valueOf("application/javascript")).body(js);
                    }

                    @GetMapping(value = "/generated/trusted-source/state/{conceptName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> trustedSourceState(
                            @PathVariable String conceptName,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        ResponseEntity<Map<String, Object>> rejection = rejectIfUnauthorized(context, Map.of(), "ADMIN", false, 0);
                        if (rejection != null) {
                            return rejection;
                        }
                        List<ConceptRecord> records = conceptGateway.list(
                                new ConceptListRequest(conceptName, context.tenantId()),
                                context
                        );
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (ConceptRecord record : records) {
                            Map<String, Object> row = new LinkedHashMap<>(record.data());
                            row.put("id", record.id());
                            row.put("tenantId", record.tenantId());
                            rows.add(row);
                        }
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "ok");
                        response.put("conceptName", conceptName);
                        response.put("tenantId", context.tenantId());
                        response.put("count", rows.size());
                        response.put("records", rows);
                        return ResponseEntity.ok(response);
                    }

                    private ResponseEntity<Map<String, Object>> rejectIfUnauthorized(
                            ExecutionContext context,
                            Map<String, Object> input,
                            String requiredRole,
                            boolean tenantScoped,
                            int before
                    ) {
                        if (!context.hasRole(requiredRole)) {
                            return rejected("missing-role", before);
                        }
                        if (tenantScoped) {
                            String requestedTenant = stringValue(input.get("tenantId"));
                            if (!requestedTenant.isBlank() && !requestedTenant.equals(context.tenantId())) {
                                return rejected("wrong-tenant", before);
                            }
                        }
                        return null;
                    }

                    private ResponseEntity<Map<String, Object>> rejected(String reason, int before) {
                        Map<String, Object> response = new LinkedHashMap<>();
                        response.put("status", "rejected");
                        response.put("reason", reason);
                        response.put("sideEffectCountBefore", before);
                        response.put("sideEffectCountAfter", before);
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
                    }

                    private int runtimeCount(ExecutionContext context, String conceptName) {
                        return conceptGateway.list(new ConceptListRequest(conceptName, context.tenantId()), context).size();
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private final class GeneratedTrustedProcedureContext implements NPDevProcedureContext {
                        private final ExecutionContext context;

                        private GeneratedTrustedProcedureContext(ExecutionContext context) {
                            this.context = context;
                        }

                        @Override
                        public String tenantId() {
                            return context.tenantId();
                        }

                        @Override
                        public String actorId() {
                            return context.actorId();
                        }

                        @Override
                        public List<Map<String, Object>> saveMany(String concept, List<Map<String, Object>> records) {
                            List<Map<String, Object>> saved = new ArrayList<>();
                            for (Map<String, Object> record : records == null ? List.<Map<String, Object>>of() : records) {
                                String id = stringValue(record.get("id"));
                                Map<String, Object> data = new LinkedHashMap<>(record);
                                data.put("tenantId", context.tenantId());
                                ConceptRecord savedRecord = conceptGateway.save(
                                        new ConceptWriteRequest(concept, id, context.tenantId(), data),
                                        context
                                );
                                Map<String, Object> row = new LinkedHashMap<>(savedRecord.data());
                                row.put("id", savedRecord.id());
                                row.put("tenantId", savedRecord.tenantId());
                                saved.add(row);
                            }
                            return List.copyOf(saved);
                        }
                    }
                }
                """);
        return source.toString();
    }

    private static String generationManifest(
            List<ManifestEntry> entries,
            List<TrustedProcedure> procedures,
            List<TrustedPanel> panels,
            Path manifestPath
    ) throws IOException {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", "npdev-generated-trusted-source-manifest.v1");
        output.put("manifestPath", manifestPath.toString());
        output.put("overlayHarnessUsed", false);
        output.put("procedureCount", procedures.size());
        output.put("panelCount", panels.size());
        output.put("entries", entries);
        return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
    }

    private static String key(String kind, String relativePath) {
        return kind + "::" + relativePath.replace('\\', '/');
    }

    private static String metadataText(Map<String, Object> metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        return value == null ? "" : String.valueOf(value).trim();
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

    private static String fileName(String relativePath) {
        return Path.of(relativePath.replace('\\', '/')).getFileName().toString();
    }

    private static String safeResourceName(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "trusted-panel" : normalized;
    }

    private static String methodSuffix(String value) {
        StringBuilder out = new StringBuilder();
        boolean capitalizeNext = true;
        for (char ch : value.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                out.append(capitalizeNext ? Character.toUpperCase(ch) : ch);
                capitalizeNext = false;
            } else {
                capitalizeNext = true;
            }
        }
        return out.isEmpty() ? "TrustedSource" : out.toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record TrustedReference(
            String kind,
            String id,
            String route,
            String relativePath,
            String requiredRole,
            Object source
    ) {
    }

    private record ManifestEntry(
            String entryId,
            String kind,
            String relativePath,
            String language,
            String sha256,
            String runtimeBinding,
            String className,
            String method,
            String requiredRole,
            boolean tenantScoped
    ) {
    }

    private record TrustedProcedure(
            String id,
            String relativePath,
            String className,
            String method,
            String requiredRole,
            boolean tenantScoped,
            String source
    ) {
    }

    private record TrustedPanel(
            String id,
            String route,
            String relativePath,
            String resourceName,
            String cssResourceName,
            String jsResourceName,
            String requiredRole,
            boolean tenantScoped,
            String source,
            String cssSource,
            String jsSource
    ) {
    }

    private record PanelAssets(
            String html,
            String css,
            String js
    ) {
    }
}
