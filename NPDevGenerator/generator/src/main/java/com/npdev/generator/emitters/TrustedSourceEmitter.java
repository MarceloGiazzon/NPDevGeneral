package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public final class TrustedSourceEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String PACKAGE_NAME = "com.npdev.generated.trusted";
    private static final String PACKAGE_PATH = "com/npdev/generated/trusted";
    private static final String FULL_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; form-action 'self'; object-src 'none'; base-uri 'self'; frame-src 'none'; frame-ancestors 'none'; worker-src 'none'; manifest-src 'self'; upgrade-insecure-requests";
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
    private static final Safelist PANEL_HTML_SAFELIST = Safelist.relaxed()
            .addTags("button", "main", "section", "article", "nav", "header", "footer", "template")
            .addAttributes(":all", "class", "id", "title", "role", "aria-label", "aria-describedby", "aria-controls", "aria-expanded", "aria-live")
            .addAttributes("button", "type", "name", "value")
            .addAttributes("input", "type", "name", "value", "placeholder", "checked", "disabled", "readonly")
            .addAttributes("label", "for")
            .addAttributes("form", "method")
            .preserveRelativeLinks(true);
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
        List<TrustedFlow> flows = trustedFlowsFrom(model);

        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/NPDevProcedureContext.java",
                procedureContextSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionDescriptor.java",
                generatedActionDescriptorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionExecutionRequest.java",
                generatedActionExecutionRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionExecutionResponse.java",
                generatedActionExecutionResponseSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityRequest.java",
                generatedActionCapabilityRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityResult.java",
                generatedActionCapabilityResultSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityAdapter.java",
                generatedActionCapabilityAdapterSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityDispatcherFactory.java",
                generatedActionCapabilityDispatcherFactorySource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityRegistryContributor.java",
                generatedActionCapabilityRegistryContributorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionKernelRunner.java",
                generatedActionKernelRunnerSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowDescriptor.java",
                generatedFlowDescriptorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowExecutionRequest.java",
                generatedFlowExecutionRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowExecutionResponse.java",
                generatedFlowExecutionResponseSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowCodaRunner.java",
                generatedFlowCodaRunnerSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowRegistry.java",
                generatedFlowRegistrySource(flows)
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionRegistry.java",
                generatedActionRegistrySource(procedures)
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
                controllerSource(procedures, panels, flows)
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

    private static List<TrustedFlow> trustedFlowsFrom(CompiledModel model) {
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
                reference.source() instanceof CompiledProcedure compiledProcedure
                        ? compiledProcedure.actionDescriptor()
                        : null,
                reference.source() instanceof CompiledProcedure compiledProcedure
                        ? compiledProcedure.metadata()
                        : Map.of(),
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

    private static final class TrustedJavaSourcePolicyVisitor extends TreeScanner<Void, Void> {
        private final List<String> violations;

        private TrustedJavaSourcePolicyVisitor(List<String> violations) {
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
    }

    private static final class InMemoryTrustedJavaSource extends SimpleJavaFileObject {
        private final String source;

        private InMemoryTrustedJavaSource(String relativePath, String source) {
            super(URI.create("string:///" + relativePath.replace('\\', '/')), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static void validatePanelSource(String source, String relativePath) {
        sanitizePanelAssets(source, relativePath, "validation");
    }

    private static PanelAssets externalizePanelAssets(String source, String resourcePrefix) {
        return sanitizePanelAssets(source, resourcePrefix, resourcePrefix);
    }

    private static PanelAssets sanitizePanelAssets(String source, String relativePath, String resourcePrefix) {
        Document document = Jsoup.parse(source, "", Parser.htmlParser());
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        StringBuilder css = new StringBuilder();
        StringBuilder js = new StringBuilder();
        validatePanelDom(document, relativePath);
        for (Element style : document.select("style")) {
            css.append(style.data()).append("\n");
            style.remove();
        }
        for (Element script : document.select("script")) {
            if (script.hasAttr("src")) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": script src");
            }
            js.append(script.data()).append("\n");
            script.remove();
        }
        validatePanelCss(css.toString(), relativePath);
        validatePanelJavaScript(js.toString(), relativePath);

        Document cleaned = new Cleaner(PANEL_HTML_SAFELIST).clean(document);
        cleaned.outputSettings(new Document.OutputSettings().prettyPrint(false));
        if (!"validation".equals(resourcePrefix)) {
            cleaned.head().appendElement("link")
                    .attr("rel", "stylesheet")
                    .attr("href", "/generated/trusted-source/panel/" + resourcePrefix + ".css");
            cleaned.body().appendElement("script")
                    .attr("src", "/generated/trusted-source/panel/" + resourcePrefix + ".js");
        }
        String html = cleaned.outerHtml();
        if (Jsoup.parse(html).select("style,script:not([src])").size() > 0) {
            throw new IllegalStateException("Trusted panel sanitizer left inline style/script in generated HTML.");
        }
        return new PanelAssets(html, css.toString(), js.toString());
    }

    private static void validatePanelDom(Document document, String relativePath) {
        for (Element element : document.getAllElements()) {
            String tag = element.normalName();
            if (Set.of("iframe", "object", "embed", "base", "meta", "svg", "math").contains(tag)) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": element " + tag);
            }
            for (org.jsoup.nodes.Attribute attribute : element.attributes()) {
                String name = attribute.getKey().toLowerCase(Locale.ROOT);
                String value = attribute.getValue().trim();
                if (name.startsWith("on")) {
                    throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": inline event handler " + name);
                }
                if (Set.of("src", "href", "action", "formaction", "poster").contains(name) && isForbiddenPanelUrl(value)) {
                    throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": unsafe URL attribute " + name);
                }
                if ("style".equals(name)) {
                    validatePanelCss(value, relativePath);
                }
            }
        }
    }

    private static boolean isForbiddenPanelUrl(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\p{Cntrl}", "").trim();
        return normalized.startsWith("http://")
                || normalized.startsWith("https://")
                || normalized.startsWith("//")
                || normalized.startsWith("javascript:")
                || normalized.startsWith("data:text/html")
                || normalized.startsWith("/.") 
                || (normalized.startsWith("/") && !normalized.startsWith("/generated/"));
    }

    private static void validatePanelCss(String css, String relativePath) {
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("css import", "(?i)@import\\s+"),
                Map.entry("css external url", "(?i)url\\s*\\(\\s*['\"]?\\s*(https?:)?//"),
                Map.entry("css javascript url", "(?i)url\\s*\\(\\s*['\"]?\\s*javascript:")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(css).find()) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": " + pattern.getKey());
            }
        }
    }

    private static void validatePanelJavaScript(String javascript, String relativePath) {
        Map<String, String> forbidden = Map.ofEntries(
                Map.entry("external fetch URL", "(?i)\\bfetch\\s*\\(\\s*['\"]\\s*(https?:)?//"),
                Map.entry("non-generated same-origin fetch", "(?i)\\bfetch\\s*\\(\\s*['\"]/(?!generated/)"),
                Map.entry("websocket URL", "(?i)\\bnew\\s+WebSocket\\s*\\(\\s*['\"]\\s*(wss?:)?//"),
                Map.entry("eval", "\\beval\\s*\\("),
                Map.entry("Function constructor", "\\bnew\\s+Function\\s*\\("),
                Map.entry("dynamic import", "\\bimport\\s*\\("),
                Map.entry("document cookie", "(?i)\\bdocument\\.cookie\\b"),
                Map.entry("local storage write", "(?i)\\blocalStorage\\.setItem\\s*\\(")
        );
        for (Map.Entry<String, String> pattern : forbidden.entrySet()) {
            if (Pattern.compile(pattern.getValue()).matcher(javascript).find()) {
                throw new IllegalStateException("Forbidden panel source use in " + relativePath + ": " + pattern.getKey());
            }
        }
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

    private static String generatedActionDescriptorSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.List;
                import java.util.Map;

                public record GeneratedActionDescriptor(
                        String actionName,
                        String procedureName,
                        String requiredRole,
                        boolean tenantScoped,
                        List<String> affectedConcepts,
                        String sideEffectConcept,
                        String eventNameOnSuccess,
                        String auditResourceType,
                        String idempotencyPolicy,
                        String tracePolicy,
                        String correlationPolicy,
                        String capabilityId,
                        Handler handler
                ) {
                    public GeneratedActionDescriptor {
                        actionName = require(actionName, "actionName");
                        procedureName = require(procedureName, "procedureName");
                        requiredRole = require(requiredRole, "requiredRole");
                        affectedConcepts = affectedConcepts == null ? List.of() : List.copyOf(affectedConcepts);
                        sideEffectConcept = clean(sideEffectConcept);
                        eventNameOnSuccess = require(eventNameOnSuccess, "eventNameOnSuccess");
                        auditResourceType = require(auditResourceType, "auditResourceType");
                        idempotencyPolicy = require(idempotencyPolicy, "idempotencyPolicy");
                        tracePolicy = require(tracePolicy, "tracePolicy");
                        correlationPolicy = require(correlationPolicy, "correlationPolicy");
                        capabilityId = require(capabilityId, "capabilityId");
                        if (handler == null) {
                            throw new IllegalArgumentException("handler must be non-null");
                        }
                    }

                    private static String require(String value, String field) {
                        if (value == null || value.trim().isEmpty()) {
                            throw new IllegalArgumentException(field + " must be non-blank");
                        }
                        return value.trim();
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }

                    @FunctionalInterface
                    public interface Handler {
                        Map<String, Object> invoke(NPDevProcedureContext context);
                    }
                }
                """;
    }

    private static String generatedActionExecutionRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionExecutionRequest(
                        String executionId,
                        String correlationId,
                        String idempotencyKey,
                        Map<String, Object> input
                ) {
                    public GeneratedActionExecutionRequest {
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        idempotencyKey = clean(idempotencyKey);
                        input = input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
                    }

                    public static GeneratedActionExecutionRequest from(Map<String, Object> body) {
                        Map<String, Object> input = body == null ? Map.of() : new LinkedHashMap<>(body);
                        return new GeneratedActionExecutionRequest(
                                stringValue(input.remove("executionId")),
                                stringValue(input.remove("correlationId")),
                                stringValue(input.remove("idempotencyKey")),
                                input
                        );
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value);
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedActionExecutionResponseSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionExecutionResponse(
                        String status,
                        String actionName,
                        String procedureName,
                        String executionId,
                        String correlationId,
                        String capabilityId,
                        String capabilityDispatchStatus,
                        int createdCount,
                        int sideEffectCountBefore,
                        int sideEffectCountAfter,
                        String eventStatus,
                        String auditStatus,
                        String traceStatus,
                        String idempotencyStatus,
                        String correlationStatus,
                        String message,
                        String error,
                        Map<String, Object> result
                ) {
                    public GeneratedActionExecutionResponse {
                        status = clean(status);
                        actionName = clean(actionName);
                        procedureName = clean(procedureName);
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        capabilityId = clean(capabilityId);
                        capabilityDispatchStatus = clean(capabilityDispatchStatus);
                        eventStatus = clean(eventStatus);
                        auditStatus = clean(auditStatus);
                        traceStatus = clean(traceStatus);
                        idempotencyStatus = clean(idempotencyStatus);
                        correlationStatus = clean(correlationStatus);
                        message = clean(message);
                        error = clean(error);
                        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("actionName", actionName);
                        out.put("procedureName", procedureName);
                        out.put("executionId", executionId);
                        out.put("correlationId", correlationId);
                        out.put("capabilityId", capabilityId);
                        out.put("capabilityDispatchStatus", capabilityDispatchStatus);
                        out.put("createdCount", createdCount);
                        out.put("sideEffectCountBefore", sideEffectCountBefore);
                        out.put("sideEffectCountAfter", sideEffectCountAfter);
                        out.put("eventStatus", eventStatus);
                        out.put("auditStatus", auditStatus);
                        out.put("traceStatus", traceStatus);
                        out.put("idempotencyStatus", idempotencyStatus);
                        out.put("correlationStatus", correlationStatus);
                        out.put("message", message);
                        out.put("error", error);
                        if (!result.isEmpty()) {
                            out.put("result", result);
                        }
                        return out;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedActionCapabilityRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.ExecutionContext;

                public record GeneratedActionCapabilityRequest(
                        GeneratedActionDescriptor descriptor,
                        GeneratedActionExecutionRequest executionRequest,
                        NPDevProcedureContext procedureContext,
                        ExecutionContext executionContext,
                        String executionId,
                        String correlationId
                ) {
                    public GeneratedActionCapabilityRequest {
                        if (descriptor == null) {
                            throw new IllegalArgumentException("descriptor must be non-null");
                        }
                        if (executionRequest == null) {
                            throw new IllegalArgumentException("executionRequest must be non-null");
                        }
                        if (procedureContext == null) {
                            throw new IllegalArgumentException("procedureContext must be non-null");
                        }
                        if (executionContext == null) {
                            throw new IllegalArgumentException("executionContext must be non-null");
                        }
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedActionCapabilityResultSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionCapabilityResult(
                        String status,
                        Map<String, Object> result,
                        boolean dispatcherEntered,
                        boolean providerEntered,
                        boolean handlerInvoked,
                        String message,
                        String error
                ) {
                    public GeneratedActionCapabilityResult {
                        status = clean(status);
                        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
                        message = clean(message);
                        error = clean(error);
                    }

                    public static GeneratedActionCapabilityResult ok(Map<String, Object> result) {
                        return new GeneratedActionCapabilityResult("ok", result, true, true, true, "ok", "");
                    }

                    public static GeneratedActionCapabilityResult error(String message, String error) {
                        return new GeneratedActionCapabilityResult("error", Map.of(), true, true, false, message, error);
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("dispatcherEntered", dispatcherEntered);
                        out.put("providerEntered", providerEntered);
                        out.put("handlerInvoked", handlerInvoked);
                        out.put("message", message);
                        out.put("error", error);
                        out.put("result", result);
                        out.putAll(result);
                        return out;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedActionCapabilityAdapterSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityCall;
                import com.npdev.kernel.CapabilityErrorKind;
                import com.npdev.kernel.CapabilityResult;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import com.npdev.kernel.ports.CapabilityAdapter;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedActionCapabilityAdapter implements CapabilityAdapter {
                    public static final String CAPABILITY_TYPE = "GeneratedActionCapability";
                    public static final String ADAPTER_ID = "generated-action";
                    public static final String OPERATION_RUN = "run";
                    private final ConceptGateway conceptGateway;

                    public GeneratedActionCapabilityAdapter() {
                        this(null);
                    }

                    public GeneratedActionCapabilityAdapter(ConceptGateway conceptGateway) {
                        this.conceptGateway = conceptGateway;
                    }

                    @Override
                    public String adapterId() {
                        return ADAPTER_ID;
                    }

                    @Override
                    public String capability() {
                        return CAPABILITY_TYPE;
                    }

                    @Override
                    public String capabilityType() {
                        return CAPABILITY_TYPE;
                    }

                    @Override
                    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
                        if (conceptGateway != null) {
                            GeneratedActionCapabilityDispatcherFactory.dispatcherEntered();
                        }
                        GeneratedActionCapabilityDispatcherFactory.providerEntered();
                        if (call == null || !OPERATION_RUN.equals(call.operation())) {
                            return CapabilityResult.failure(
                                    "GENERATED_ACTION_OPERATION_UNSUPPORTED",
                                    "Generated action capability only supports operation run",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of()
                            );
                        }
                        Object input = call.input();
                        GeneratedActionCapabilityRequest request = null;
                        if (input instanceof GeneratedActionCapabilityRequest richRequest) {
                            request = richRequest;
                        } else if (conceptGateway != null) {
                            request = requestFromKernelFlow(call, contextState);
                        }
                        if (request == null) {
                            return CapabilityResult.failure(
                                    "GENERATED_ACTION_REQUEST_INVALID",
                                    "Generated action capability requires GeneratedActionCapabilityRequest",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of()
                            );
                        }
                        try {
                            Map<String, Object> rawResult = request.descriptor().handler().invoke(request.procedureContext());
                            Map<String, Object> safeResult = rawResult == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(rawResult));
                            GeneratedActionCapabilityDispatcherFactory.handlerInvoked();
                            return CapabilityResult.success(GeneratedActionCapabilityResult.ok(safeResult));
                        } catch (RuntimeException exception) {
                            String error = exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                            return CapabilityResult.success(GeneratedActionCapabilityResult.error("handler failed", error));
                        }
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }

                    @SuppressWarnings("unchecked")
                    private GeneratedActionCapabilityRequest requestFromKernelFlow(
                            CapabilityCall call,
                            Map<String, Object> contextState
                    ) {
                        Map<String, Object> state = contextState == null ? Map.of() : contextState;
                        String capability = clean(call.capability());
                        String actionName = capability.startsWith("generated.action.")
                                ? capability.substring("generated.action.".length())
                                : capability;
                        GeneratedActionDescriptor descriptor = GeneratedActionRegistry.find(actionName);
                        if (descriptor == null) {
                            return null;
                        }
                        Map<String, Object> input = new LinkedHashMap<>();
                        Object rawInput = call.input();
                        if (rawInput instanceof Map<?, ?> map) {
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                input.put(String.valueOf(entry.getKey()), entry.getValue());
                            }
                        } else {
                            Object stateInput = state.get("input");
                            if (stateInput instanceof Map<?, ?> map) {
                                for (Map.Entry<?, ?> entry : map.entrySet()) {
                                    input.put(String.valueOf(entry.getKey()), entry.getValue());
                                }
                            }
                        }
                        String executionId = stringValue(state.get("executionId"));
                        String correlationId = firstNonBlank(stringValue(state.get("correlationId")), call.correlationId());
                        String idempotencyKey = firstNonBlank(call.idempotencyKey(), stringValue(input.get("idempotencyKey")));
                        GeneratedActionExecutionRequest executionRequest = new GeneratedActionExecutionRequest(
                                executionId,
                                correlationId,
                                idempotencyKey,
                                input
                        );
                        ExecutionContext executionContext = ExecutionContext.of(
                                firstNonBlank(stringValue(state.get("tenantId")), "dev"),
                                firstNonBlank(stringValue(state.get("actorId")), "system")
                        ).withTag("executionId", executionId).withTag("correlationId", correlationId);
                        if (!idempotencyKey.isBlank()) {
                            executionContext = executionContext.withTag("idempotencyKey", idempotencyKey);
                        }
                        return new GeneratedActionCapabilityRequest(
                                descriptor,
                                executionRequest,
                                new AdapterProcedureContext(conceptGateway, executionContext),
                                executionContext,
                                executionId,
                                correlationId
                        );
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            if (value != null && !value.isBlank()) {
                                return value.trim();
                            }
                        }
                        return "";
                    }

                    private static final class AdapterProcedureContext implements NPDevProcedureContext {
                        private final ConceptGateway conceptGateway;
                        private final ExecutionContext context;

                        private AdapterProcedureContext(ConceptGateway conceptGateway, ExecutionContext context) {
                            this.conceptGateway = conceptGateway;
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
                """;
    }

    private static String generatedActionCapabilityDispatcherFactorySource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityRegistry;
                import com.npdev.kernel.RegistryCapabilityDispatcher;
                import com.npdev.kernel.ports.CapabilityDispatcher;

                import java.util.concurrent.atomic.AtomicInteger;

                public final class GeneratedActionCapabilityDispatcherFactory {
                    private static final AtomicInteger DISPATCHER_INVOCATIONS = new AtomicInteger();
                    private static final AtomicInteger PROVIDER_INVOCATIONS = new AtomicInteger();
                    private static final AtomicInteger HANDLER_INVOCATIONS = new AtomicInteger();

                    private GeneratedActionCapabilityDispatcherFactory() {
                    }

                    public static CapabilityDispatcher create() {
                        CapabilityRegistry registry = new CapabilityRegistry();
                        GeneratedActionCapabilityAdapter adapter = new GeneratedActionCapabilityAdapter();
                        for (GeneratedActionDescriptor descriptor : GeneratedActionRegistry.all()) {
                            registry.register(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    adapter
                            );
                        }
                        CapabilityDispatcher delegate = new RegistryCapabilityDispatcher(registry);
                        return (call, contextState) -> {
                            DISPATCHER_INVOCATIONS.incrementAndGet();
                            return delegate.invoke(call, contextState);
                        };
                    }

                    public static void providerEntered() {
                        PROVIDER_INVOCATIONS.incrementAndGet();
                    }

                    public static void dispatcherEntered() {
                        DISPATCHER_INVOCATIONS.incrementAndGet();
                    }

                    public static void handlerInvoked() {
                        HANDLER_INVOCATIONS.incrementAndGet();
                    }

                    public static int dispatcherInvocations() {
                        return DISPATCHER_INVOCATIONS.get();
                    }

                    public static int providerInvocations() {
                        return PROVIDER_INVOCATIONS.get();
                    }

                    public static int handlerInvocations() {
                        return HANDLER_INVOCATIONS.get();
                    }

                    public static void resetCounters() {
                        DISPATCHER_INVOCATIONS.set(0);
                        PROVIDER_INVOCATIONS.set(0);
                        HANDLER_INVOCATIONS.set(0);
                    }
                }
                """;
    }

    private static String generatedActionCapabilityRegistryContributorSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityRegistry;
                import com.npdev.kernel.concepts.ConceptGateway;
                import org.springframework.beans.factory.SmartInitializingSingleton;
                import org.springframework.stereotype.Component;

                @Component
                public final class GeneratedActionCapabilityRegistryContributor implements SmartInitializingSingleton {
                    private final CapabilityRegistry capabilityRegistry;
                    private final ConceptGateway conceptGateway;

                    public GeneratedActionCapabilityRegistryContributor(
                            CapabilityRegistry capabilityRegistry,
                            ConceptGateway conceptGateway
                    ) {
                        this.capabilityRegistry = capabilityRegistry;
                        this.conceptGateway = conceptGateway;
                    }

                    @Override
                    public void afterSingletonsInstantiated() {
                        GeneratedActionCapabilityAdapter adapter = new GeneratedActionCapabilityAdapter(conceptGateway);
                        for (GeneratedActionDescriptor descriptor : GeneratedActionRegistry.all()) {
                            capabilityRegistry.register(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    adapter
                            );
                        }
                    }
                }
                """;
    }

    private static String generatedActionKernelRunnerSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.kernel.CapabilityCall;
                import com.npdev.kernel.CapabilityResult;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.capability.IdempotencyRecord;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import com.npdev.kernel.events.EventEnvelope;
                import com.npdev.kernel.ports.CapabilityDispatcher;
                import org.springframework.stereotype.Service;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.UUID;

                @Service
                public class GeneratedActionKernelRunner {
                    private final ConceptGateway conceptGateway;
                    private final KernelFacade kernelFacade;
                    private final CapabilityDispatcher capabilityDispatcher;

                    public GeneratedActionKernelRunner(
                            ConceptGateway conceptGateway,
                            KernelFacade kernelFacade
                    ) {
                        this.conceptGateway = conceptGateway;
                        this.kernelFacade = kernelFacade;
                        this.capabilityDispatcher = GeneratedActionCapabilityDispatcherFactory.create();
                    }

                    public GeneratedActionExecutionResponse run(
                            String actionName,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context
                    ) {
                        GeneratedActionExecutionRequest safeRequest = request == null
                                ? new GeneratedActionExecutionRequest("", "", "", Map.of())
                                : request;
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        String executionId = firstNonBlank(safeRequest.executionId(), UUID.randomUUID().toString());
                        String correlationId = firstNonBlank(
                                safeRequest.correlationId(),
                                safeContext.correlationId(),
                                UUID.randomUUID().toString()
                        );
                        ExecutionContext actionContext = safeContext
                                .withTag("executionId", executionId)
                                .withTag("correlationId", correlationId);
                        if (!safeRequest.idempotencyKey().isBlank()) {
                            actionContext = actionContext.withTag("idempotencyKey", safeRequest.idempotencyKey());
                        }

                        GeneratedActionDescriptor descriptor = GeneratedActionRegistry.find(actionName);
                        if (descriptor == null) {
                            return response(
                                    "rejected",
                                    clean(actionName),
                                     "",
                                     executionId,
                                     correlationId,
                                     "unavailable: action not found",
                                     "unavailable: action not found before capability dispatch",
                                     0,
                                     0,
                                    "unavailable: action not found, no event published",
                                    "unavailable: no generated action executed",
                                    "unavailable: generated action runner has no direct trace store API",
                                    idempotencyEvidenceStatus(safeRequest),
                                    "tagged: correlationId present on ExecutionContext only",
                                    "unknown-action",
                                    "unknown-action",
                                    Map.of()
                            );
                        }

                        int before = runtimeCount(actionContext, descriptor.sideEffectConcept());
                        String sideEffectCountingStatus = sideEffectCountingStatus(descriptor);
                        String correlationStatus = claimCorrelation(correlationId, actionContext);
                        String authorizationFailure = authorizationFailure(descriptor, safeRequest.input(), actionContext);
                        if (!authorizationFailure.isBlank()) {
                            String auditStatus = recordAudit(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "denied",
                                    authorizationFailure,
                                    actionContext
                            );
                            return response(
                                    "rejected",
                                    descriptor.actionName(),
                                     descriptor.procedureName(),
                                     executionId,
                                     correlationId,
                                     descriptor.capabilityId(),
                                     "prevented: authorization rejected before capability dispatch",
                                     before,
                                     before,
                                    "unavailable: authorization rejected before event publication",
                                    auditStatus,
                                    "unavailable: generated action runner has no direct trace store API",
                                    idempotencyEvidenceStatus(safeRequest),
                                    correlationStatus,
                                    authorizationFailure,
                                    authorizationFailure,
                                    Map.of()
                            );
                        }

                        Optional<IdempotencyRecord> cachedRecord = findIdempotency(descriptor, safeRequest, actionContext);
                        if (cachedRecord.isPresent() && cachedRecord.get().success()) {
                            String auditStatus = recordAudit(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "reused",
                                    "idempotency_reused",
                                    actionContext
                            );
                            String traceStatus = writeTrace(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "ok",
                                    System.currentTimeMillis(),
                                    System.currentTimeMillis(),
                                    before,
                                    before,
                                    actionContext
                            );
                            return response(
                                    "ok",
                                    descriptor.actionName(),
                                     descriptor.procedureName(),
                                     executionId,
                                     correlationId,
                                     descriptor.capabilityId(),
                                     "prevented: idempotency reused before capability dispatch",
                                     before,
                                     before,
                                    "unavailable: idempotent reuse prevented duplicate side effects; no success event republished",
                                    auditStatus,
                                    traceStatus,
                                    "reused: idempotencyKey=" + safeRequest.idempotencyKey(),
                                    correlationStatus,
                                    "idempotent-reuse",
                                    "",
                                    withSideEffectCountingStatus(
                                            Map.of("idempotencyReused", true, "sideEffectPrevented", true),
                                            sideEffectCountingStatus
                                    )
                            );
                        }

                        Map<String, Object> result = Map.of();
                        String error = "";
                        String message = "ok";
                        String status = "ok";
                        String capabilityDispatchStatus = "unavailable: capability dispatch not attempted";
                        long startedAtMs = System.currentTimeMillis();
                        String auditStatus = recordAudit(
                                descriptor,
                                executionId,
                                correlationId,
                                "started",
                                "handler_start",
                                actionContext
                        );
                        try {
                            GeneratedTrustedProcedureContext trustedContext = new GeneratedTrustedProcedureContext(actionContext);
                            GeneratedActionCapabilityRequest capabilityRequest = new GeneratedActionCapabilityRequest(
                                    descriptor,
                                    safeRequest,
                                    trustedContext,
                                    actionContext,
                                    executionId,
                                    correlationId
                            );
                            CapabilityCall capabilityCall = new CapabilityCall(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    GeneratedActionCapabilityAdapter.OPERATION_RUN,
                                    List.of(capabilityRequest),
                                    correlationId,
                                    safeRequest.idempotencyKey().isBlank() ? null : safeRequest.idempotencyKey()
                            );
                            CapabilityResult capabilityResult = capabilityDispatcher.invoke(
                                     capabilityCall,
                                     capabilityContextState(descriptor, safeRequest, actionContext, executionId, correlationId)
                             );
                            capabilityDispatchStatus = "dispatched: capabilityId=" + descriptor.capabilityId();
                             if (!capabilityResult.ok()) {
                                 status = "error";
                                 error = capabilityResult.error().code() + ": " + capabilityResult.error().message();
                                 message = "capability dispatch failed";
                                 capabilityDispatchStatus = "failed: " + error;
                             } else if (capabilityResult.value() instanceof GeneratedActionCapabilityResult actionResult) {
                                 status = actionResult.status();
                                 message = actionResult.message().isBlank() ? status : actionResult.message();
                                 error = actionResult.error();
                                 result = actionResult.result();
                            } else {
                                status = "error";
                                error = "Generated action capability returned unexpected result type";
                                message = "capability dispatch failed";
                            }
                        } catch (RuntimeException exception) {
                             status = "error";
                             error = exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                             message = "handler failed";
                             capabilityDispatchStatus = capabilityDispatchStatus.startsWith("dispatched")
                                     ? "failed: " + error
                                     : "failed: exception before capability dispatch: " + error;
                         }

                        int after = runtimeCount(actionContext, descriptor.sideEffectConcept());
                        long endedAtMs = System.currentTimeMillis();
                        String eventStatus = "ok".equals(status)
                                ? publishActionEvent(descriptor, safeRequest, actionContext, executionId, correlationId, status, after - before)
                                : "unavailable: failed action did not publish success event";
                        String traceStatus = writeTrace(
                                descriptor,
                                executionId,
                                correlationId,
                                status,
                                startedAtMs,
                                endedAtMs,
                                before,
                                after,
                                actionContext
                        );
                        String completionAuditStatus = recordAudit(
                                descriptor,
                                executionId,
                                correlationId,
                                "ok".equals(status) ? "completed" : "failed",
                                "ok".equals(status) ? "ok" : error,
                                actionContext
                        );
                        if (completionAuditStatus.startsWith("written")) {
                            auditStatus = completionAuditStatus;
                        }
                        String idempotencyStatus = recordIdempotency(descriptor, safeRequest, actionContext, status, result, error);
                        return response(
                                status,
                                descriptor.actionName(),
                                 descriptor.procedureName(),
                                 executionId,
                                 correlationId,
                                 descriptor.capabilityId(),
                                 capabilityDispatchStatus,
                                 before,
                                 after,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                idempotencyStatus,
                                correlationStatus,
                                message,
                                error,
                                withSideEffectCountingStatus(result, sideEffectCountingStatus)
                        );
                    }

                    private static Map<String, Object> capabilityContextState(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String executionId,
                            String correlationId
                    ) {
                        Map<String, Object> state = new LinkedHashMap<>();
                        state.put("actionName", descriptor.actionName());
                        state.put("procedureName", descriptor.procedureName());
                        state.put("executionId", executionId);
                        state.put("correlationId", correlationId);
                        state.put("tenantId", context.tenantId());
                        state.put("actorId", context.actorId());
                        state.put("input", request.input());
                        state.put("dispatchModel", "CapabilityDispatcher");
                        return Map.copyOf(state);
                    }

                    private String publishActionEvent(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String executionId,
                            String correlationId,
                            String status,
                            int createdCount
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("actionName", descriptor.actionName());
                        payload.put("procedureName", descriptor.procedureName());
                        payload.put("executionId", executionId);
                        payload.put("correlationId", correlationId);
                        payload.put("status", status);
                        payload.put("createdCount", createdCount);
                        payload.put("input", request.input());
                        try {
                            EventEnvelope envelope = kernelFacade.publishExternalEvent(
                                    descriptor.eventNameOnSuccess(),
                                    correlationId,
                                    executionId,
                                    payload,
                                    context
                            );
                            return kernelFacade.verifyGeneratedActionEvent(
                                    descriptor.eventNameOnSuccess(),
                                    correlationId,
                                    envelope.eventId(),
                                    context
                            );
                        } catch (RuntimeException exception) {
                            return "failed: " + exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                        }
                    }

                    private String claimCorrelation(String correlationId, ExecutionContext context) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        return kernelFacade.claimGeneratedActionCorrelation(correlationId, context);
                    }

                    private Optional<IdempotencyRecord> findIdempotency(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null
                                || request == null
                                || request.idempotencyKey().isBlank()
                                || "disabled".equalsIgnoreCase(descriptor.idempotencyPolicy())) {
                            return Optional.empty();
                        }
                        return kernelFacade.findGeneratedActionIdempotency(
                                descriptor.actionName(),
                                request.idempotencyKey(),
                                context
                        );
                    }

                    private String recordIdempotency(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String status,
                            Map<String, Object> result,
                            String error
                    ) {
                        if (request == null || request.idempotencyKey().isBlank()) {
                            return "disabled: no idempotencyKey supplied";
                        }
                        if ("disabled".equalsIgnoreCase(descriptor.idempotencyPolicy())) {
                            return "disabled: descriptor idempotencyPolicy is disabled";
                        }
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        if ("ok".equalsIgnoreCase(status)) {
                            return kernelFacade.recordGeneratedActionIdempotencySuccess(
                                    descriptor.actionName(),
                                    request.idempotencyKey(),
                                    result == null ? "{}" : result.toString(),
                                    context
                            );
                        }
                        return kernelFacade.recordGeneratedActionIdempotencyFailure(
                                descriptor.actionName(),
                                request.idempotencyKey(),
                                error,
                                context
                        );
                    }

                    private String recordAudit(
                            GeneratedActionDescriptor descriptor,
                            String executionId,
                            String correlationId,
                            String outcome,
                            String reasonCode,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        return kernelFacade.recordGeneratedActionAudit(
                                descriptor.actionName(),
                                descriptor.auditResourceType(),
                                executionId,
                                correlationId,
                                outcome,
                                reasonCode,
                                context
                        );
                    }

                    private String writeTrace(
                            GeneratedActionDescriptor descriptor,
                            String executionId,
                            String correlationId,
                            String outcome,
                            long startedAtMs,
                            long endedAtMs,
                            int before,
                            int after,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        if ("disabled".equalsIgnoreCase(descriptor.tracePolicy())) {
                            return "disabled: descriptor tracePolicy is disabled";
                        }
                        return kernelFacade.writeGeneratedActionTrace(
                                descriptor.actionName(),
                                executionId,
                                correlationId,
                                outcome,
                                startedAtMs,
                                endedAtMs,
                                before,
                                after,
                                context
                        );
                    }

                    private GeneratedActionExecutionResponse response(
                            String status,
                            String actionName,
                             String procedureName,
                             String executionId,
                             String correlationId,
                             String capabilityId,
                             String capabilityDispatchStatus,
                             int before,
                             int after,
                            String eventStatus,
                            String auditStatus,
                            String traceStatus,
                            String idempotencyStatus,
                            String correlationStatus,
                            String message,
                            String error,
                            Map<String, Object> result
                    ) {
                        return new GeneratedActionExecutionResponse(
                                status,
                                actionName,
                                 procedureName,
                                 executionId,
                                 correlationId,
                                 capabilityId,
                                 capabilityDispatchStatus,
                                 Math.max(0, after - before),
                                before,
                                after,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                idempotencyStatus,
                                correlationStatus,
                                message,
                                error,
                                result
                        );
                    }

                    private static String authorizationFailure(
                            GeneratedActionDescriptor descriptor,
                            Map<String, Object> input,
                            ExecutionContext context
                    ) {
                        if (!context.hasRole(descriptor.requiredRole())) {
                            return "missing-role";
                        }
                        if (descriptor.tenantScoped()) {
                            String requestedTenant = stringValue(input.get("tenantId"));
                            if (!requestedTenant.isBlank() && !requestedTenant.equals(context.tenantId())) {
                                return "wrong-tenant";
                            }
                        }
                        return "";
                    }

                    private static String idempotencyEvidenceStatus(GeneratedActionExecutionRequest request) {
                        if (request == null || request.idempotencyKey().isBlank()) {
                            return "disabled: no idempotencyKey supplied";
                        }
                        return "unavailable: action did not reach idempotency evidence check";
                    }

                    private int runtimeCount(ExecutionContext context, String conceptName) {
                        if (conceptName == null || conceptName.isBlank()) {
                            return 0;
                        }
                        try {
                            return conceptGateway.list(new ConceptListRequest(conceptName, context.tenantId()), context).size();
                        } catch (RuntimeException ignored) {
                            return 0;
                        }
                    }

                    private static String sideEffectCountingStatus(GeneratedActionDescriptor descriptor) {
                        if (descriptor == null || descriptor.sideEffectConcept().isBlank()) {
                            return "disabled: descriptor sideEffectConcept is not declared";
                        }
                        return "enabled: sideEffectConcept=" + descriptor.sideEffectConcept();
                    }

                    private static Map<String, Object> withSideEffectCountingStatus(
                            Map<String, Object> result,
                            String sideEffectCountingStatus
                    ) {
                        if (sideEffectCountingStatus == null || sideEffectCountingStatus.isBlank()
                                || sideEffectCountingStatus.startsWith("enabled:")) {
                            return result == null ? Map.of() : result;
                        }
                        Map<String, Object> out = new LinkedHashMap<>(result == null ? Map.of() : result);
                        out.put("sideEffectCountingStatus", sideEffectCountingStatus);
                        return Map.copyOf(out);
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            String cleaned = clean(value);
                            if (!cleaned.isBlank()) {
                                return cleaned;
                            }
                        }
                        return "";
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
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
                """;
    }

    private static String generatedActionRegistrySource(List<TrustedProcedure> procedures) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedActionRegistry {
                    private static final List<GeneratedActionDescriptor> DESCRIPTORS = List.of(
                """);
        for (int index = 0; index < procedures.size(); index++) {
            TrustedProcedure procedure = procedures.get(index);
            source.append("            new GeneratedActionDescriptor(")
                    .append(quote(descriptorActionName(procedure)))
                    .append(", ")
                    .append(quote(procedure.id()))
                    .append(", ")
                    .append(quote(procedure.requiredRole()))
                    .append(", ")
                    .append(procedure.tenantScoped())
                    .append(", ")
                    .append(generatedStringList(descriptorAffectedConcepts(procedure)))
                    .append(", ")
                    .append(quote(descriptorSideEffectConcept(procedure)))
                    .append(", ")
                    .append(quote(descriptorEventNameOnSuccess(procedure)))
                    .append(", ")
                    .append(quote(descriptorAuditResourceType(procedure)))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "idempotencyPolicy", "record")))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "tracePolicy", "record")))
                    .append(", ")
                    .append(quote(descriptorPolicy(procedure, "correlationPolicy", "claim")))
                    .append(", ")
                    .append(quote(descriptorCapabilityId(procedure)))
                    .append(", context -> new ")
                    .append(procedure.className())
                    .append("().")
                    .append(procedure.method())
                    .append("(context))");
            if (index < procedures.size() - 1) {
                source.append(",");
            }
            source.append("\n");
        }
        source.append("""
                    );
                    private static final Map<String, GeneratedActionDescriptor> BY_ACTION_NAME = byActionName();

                    private GeneratedActionRegistry() {
                    }

                    public static List<GeneratedActionDescriptor> all() {
                        return DESCRIPTORS;
                    }

                    public static GeneratedActionDescriptor find(String actionName) {
                        if (actionName == null) {
                            return null;
                        }
                        return BY_ACTION_NAME.get(actionName.trim());
                    }

                    private static Map<String, GeneratedActionDescriptor> byActionName() {
                        Map<String, GeneratedActionDescriptor> out = new LinkedHashMap<>();
                        for (GeneratedActionDescriptor descriptor : DESCRIPTORS) {
                            out.put(descriptor.actionName(), descriptor);
                            out.put(descriptor.procedureName(), descriptor);
                        }
                        return Map.copyOf(out);
                    }
                }
                """);
        return source.toString();
    }

    private static String generatedFlowDescriptorSource() {
        return """
                package com.npdev.generated.trusted;

                public record GeneratedFlowDescriptor(
                        String flowName,
                        String actionName
                ) {
                    public GeneratedFlowDescriptor {
                        flowName = require(flowName, "flowName");
                        actionName = clean(actionName);
                    }

                    private static String require(String value, String label) {
                        String cleaned = clean(value);
                        if (cleaned.isBlank()) {
                            throw new IllegalArgumentException(label + " must be non-blank");
                        }
                        return cleaned;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedFlowExecutionRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedFlowExecutionRequest(
                        String executionId,
                        String correlationId,
                        String idempotencyKey,
                        Map<String, Object> input
                ) {
                    public GeneratedFlowExecutionRequest {
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        idempotencyKey = clean(idempotencyKey);
                        input = input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
                    }

                    public static GeneratedFlowExecutionRequest from(Map<String, Object> body) {
                        Map<String, Object> input = body == null ? Map.of() : new LinkedHashMap<>(body);
                        return new GeneratedFlowExecutionRequest(
                                stringValue(input.remove("executionId")),
                                stringValue(input.remove("correlationId")),
                                stringValue(input.remove("idempotencyKey")),
                                input
                        );
                    }

                    public Map<String, Object> toKernelInput() {
                        Map<String, Object> out = new LinkedHashMap<>(input);
                        if (!executionId.isBlank()) {
                            out.put("executionId", executionId);
                        }
                        if (!correlationId.isBlank()) {
                            out.put("correlationId", correlationId);
                        }
                        if (!idempotencyKey.isBlank()) {
                            out.put("idempotencyKey", idempotencyKey);
                        }
                        return out;
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value);
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedFlowExecutionResponseSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedFlowExecutionResponse(
                        String status,
                        String flowName,
                        String flowInstanceId,
                        String flowStatus,
                        String executionId,
                        String correlationId,
                        String actionName,
                        String procedureName,
                        String capabilityId,
                        String capabilityDispatchStatus,
                        int createdCount,
                        int sideEffectCountBefore,
                        int sideEffectCountAfter,
                        String eventStatus,
                        String auditStatus,
                        String traceStatus,
                        String idempotencyStatus,
                        String correlationStatus,
                        String evidenceViewerUrl,
                        String message,
                        String error,
                        Map<String, Object> result
                ) {
                    public GeneratedFlowExecutionResponse {
                        status = clean(status);
                        flowName = clean(flowName);
                        flowInstanceId = clean(flowInstanceId);
                        flowStatus = clean(flowStatus);
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        actionName = clean(actionName);
                        procedureName = clean(procedureName);
                        capabilityId = clean(capabilityId);
                        capabilityDispatchStatus = clean(capabilityDispatchStatus);
                        eventStatus = clean(eventStatus);
                        auditStatus = clean(auditStatus);
                        traceStatus = clean(traceStatus);
                        idempotencyStatus = clean(idempotencyStatus);
                        correlationStatus = clean(correlationStatus);
                        evidenceViewerUrl = clean(evidenceViewerUrl);
                        message = clean(message);
                        error = clean(error);
                        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("flowName", flowName);
                        out.put("flowInstanceId", flowInstanceId);
                        out.put("flowStatus", flowStatus);
                        out.put("flowInstanceStatus", flowStatus);
                        out.put("executionId", executionId);
                        out.put("correlationId", correlationId);
                        out.put("actionName", actionName);
                        out.put("procedureName", procedureName);
                        out.put("capabilityId", capabilityId);
                        out.put("capabilityDispatchStatus", capabilityDispatchStatus);
                        out.put("createdCount", createdCount);
                        out.put("sideEffectCountBefore", sideEffectCountBefore);
                        out.put("sideEffectCountAfter", sideEffectCountAfter);
                        out.put("eventStatus", eventStatus);
                        out.put("auditStatus", auditStatus);
                        out.put("traceStatus", traceStatus);
                        out.put("idempotencyStatus", idempotencyStatus);
                        out.put("correlationStatus", correlationStatus);
                        out.put("evidenceViewerUrl", evidenceViewerUrl);
                        out.put("message", message);
                        out.put("error", error);
                        if (!result.isEmpty()) {
                            out.put("result", result);
                        }
                        return out;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedFlowCodaRunnerSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.ExecutionResult;
                import com.npdev.kernel.ExecutionStatus;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.execution.FlowInstance;
                import org.springframework.stereotype.Service;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                @Service
                public class GeneratedFlowCodaRunner {
                    private final KernelFacade kernelFacade;
                    private final ConceptGateway conceptGateway;

                    public GeneratedFlowCodaRunner(KernelFacade kernelFacade, ConceptGateway conceptGateway) {
                        this.kernelFacade = kernelFacade;
                        this.conceptGateway = conceptGateway;
                    }

                    public GeneratedFlowExecutionResponse start(
                            String flowName,
                            GeneratedFlowExecutionRequest request,
                            ExecutionContext context
                    ) {
                        GeneratedFlowDescriptor descriptor = GeneratedFlowRegistry.find(flowName);
                        GeneratedFlowExecutionRequest safeRequest = request == null
                                ? new GeneratedFlowExecutionRequest("", "", "", Map.of())
                                : request;
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        if (descriptor == null) {
                            return new GeneratedFlowExecutionResponse(
                                    "rejected",
                                    flowName,
                                    "",
                                    "not_found",
                                    safeRequest.executionId(),
                                    safeRequest.correlationId(),
                                    "",
                                    "",
                                    "",
                                    "unavailable: flow not found before dispatch",
                                    0,
                                    0,
                                    0,
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "unavailable: flow not found",
                                    "",
                                    "unknown-flow",
                                    "unknown-flow",
                                    Map.of()
                            );
                        }
                        GeneratedActionDescriptor actionDescriptor = GeneratedActionRegistry.find(descriptor.actionName());
                        String sideEffectConcept = actionDescriptor == null ? "" : actionDescriptor.sideEffectConcept();
                        int sideEffectCountBefore = countSideEffects(sideEffectConcept, safeContext);
                        try {
                            ExecutionResult result = kernelFacade.executeFlow(
                                    descriptor.flowName(),
                                    safeRequest.toKernelInput(),
                                    safeContext
                            );
                            Optional<FlowInstance> instance = result.getExecutionId() == null
                                    ? Optional.empty()
                                    : kernelFacade.findExecution(result.getExecutionId(), safeContext);
                            Map<String, Object> actionResult = result.getOutput() instanceof GeneratedActionCapabilityResult capabilityResult
                                    ? capabilityResult.toMap()
                                    : result.getOutput() instanceof Map<?, ?> map
                                    ? toStringMap(map)
                                    : Map.of();
                            int sideEffectCountAfter = countSideEffects(sideEffectConcept, safeContext);
                            int createdCount = Math.max(0, sideEffectCountAfter - sideEffectCountBefore);
                            String flowStatus = instance.map(row -> row.status().name()).orElse(statusValue(result));
                            String correlationId = firstNonBlank(result.getCorrelationId(), safeRequest.correlationId());
                            String executionId = firstNonBlank(result.getExecutionId(), safeRequest.executionId());
                            String evidenceUrl = correlationId.isBlank()
                                    ? ""
                                    : "/generated/actions/correlations/" + urlToken(correlationId);
                            String dispatchStatus = flowStatus.equals("COMPLETED")
                                    ? "dispatched: KernelRunner -> CapabilityDispatcher -> GeneratedActionCapabilityAdapter"
                                    : "failed: KernelRunner capability dispatch did not complete";
                            if (flowStatus.equals("COMPLETED")
                                    && !safeRequest.idempotencyKey().isBlank()
                                    && createdCount == 0) {
                                dispatchStatus = "prevented: action idempotency reused before generated action adapter dispatch";
                            }
                            return new GeneratedFlowExecutionResponse(
                                    result.getStatus() == ExecutionStatus.OK ? "ok" : "error",
                                    descriptor.flowName(),
                                    executionId,
                                    flowStatus,
                                    executionId,
                                    correlationId,
                                    descriptor.actionName(),
                                    actionDescriptor == null ? "" : actionDescriptor.procedureName(),
                                    actionDescriptor == null ? "generated.action." + descriptor.actionName() : actionDescriptor.capabilityId(),
                                    dispatchStatus,
                                    createdCount,
                                    sideEffectCountBefore,
                                    sideEffectCountAfter,
                                    "written: kernel flow/action evidence available through JDBC proof",
                                    "written: KernelFacade executeFlow audit path",
                                    "written: KernelRunner flow trace path",
                                    safeRequest.idempotencyKey().isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : "recorded: KernelRunner capability idempotency policy",
                                    "owned: KernelRunner correlation ownership path",
                                    evidenceUrl,
                                    result.getStatus() == ExecutionStatus.OK ? "flow completed" : "flow failed",
                                    result.getError() == null ? "" : result.getError(),
                                    actionResult
                            );
                        } catch (RuntimeException exception) {
                            return new GeneratedFlowExecutionResponse(
                                    "error",
                                    descriptor.flowName(),
                                    safeRequest.executionId(),
                                    "FAILED",
                                    safeRequest.executionId(),
                                    safeRequest.correlationId(),
                                    descriptor.actionName(),
                                    "",
                                    "generated.action." + descriptor.actionName(),
                                    "failed: " + exception.getClass().getSimpleName(),
                                    0,
                                    0,
                                    0,
                                    "unavailable: flow failed before success event proof",
                                    "failed: KernelFacade executeFlow threw",
                                    "failed: KernelRunner flow execution threw",
                                    safeRequest.idempotencyKey().isBlank()
                                            ? "unavailable: no idempotencyKey supplied"
                                            : "failed: flow failed before idempotency proof",
                                    "unavailable: flow failed before correlation proof",
                                    "",
                                    "flow failed",
                                    exception.getClass().getSimpleName() + ": " + clean(exception.getMessage()),
                                    Map.of()
                            );
                        }
                    }

                    private int countSideEffects(String concept, ExecutionContext context) {
                        if (concept == null || concept.isBlank() || conceptGateway == null) {
                            return 0;
                        }
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        return conceptGateway.list(
                                new ConceptListRequest(concept, safeContext.tenantId()),
                                safeContext
                        ).size();
                    }

                    private static Map<String, Object> toStringMap(Map<?, ?> source) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> entry : source.entrySet()) {
                            out.put(String.valueOf(entry.getKey()), entry.getValue());
                        }
                        return out;
                    }

                    private static int number(Object value) {
                        if (value instanceof Number number) {
                            return number.intValue();
                        }
                        if (value == null) {
                            return 0;
                        }
                        try {
                            return Integer.parseInt(String.valueOf(value));
                        } catch (NumberFormatException ignored) {
                            return 0;
                        }
                    }

                    private static String statusValue(ExecutionResult result) {
                        return result == null || result.getStatus() == null ? "unknown" : result.getStatus().name();
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            if (value != null && !value.isBlank()) {
                                return value.trim();
                            }
                        }
                        return "";
                    }

                    private static String urlToken(String value) {
                        return value.replace(" ", "%20");
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    private static String generatedFlowRegistrySource(List<TrustedFlow> flows) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedFlowRegistry {
                    private static final List<GeneratedFlowDescriptor> DESCRIPTORS = List.of(
                """);
        for (int i = 0; i < flows.size(); i++) {
            TrustedFlow flow = flows.get(i);
            source.append("            new GeneratedFlowDescriptor(")
                    .append(quote(flow.flowName()))
                    .append(", ")
                    .append(quote(flow.actionName()))
                    .append(")");
            if (i + 1 < flows.size()) {
                source.append(",");
            }
            source.append("\n");
        }
        source.append("""
                    );
                    private static final Map<String, GeneratedFlowDescriptor> BY_FLOW_NAME = byFlowName();

                    private GeneratedFlowRegistry() {
                    }

                    public static List<GeneratedFlowDescriptor> all() {
                        return DESCRIPTORS;
                    }

                    public static GeneratedFlowDescriptor find(String flowName) {
                        if (flowName == null) {
                            return null;
                        }
                        return BY_FLOW_NAME.get(flowName.trim());
                    }

                    private static Map<String, GeneratedFlowDescriptor> byFlowName() {
                        Map<String, GeneratedFlowDescriptor> out = new LinkedHashMap<>();
                        for (GeneratedFlowDescriptor descriptor : DESCRIPTORS) {
                            out.put(descriptor.flowName(), descriptor);
                        }
                        return Map.copyOf(out);
                    }
                }
                """);
        return source.toString();
    }

    private static String descriptorActionName(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.actionName() != null && !descriptor.actionName().isBlank()) {
            return descriptor.actionName();
        }
        return procedure.id();
    }

    private static String descriptorCapabilityId(TrustedProcedure procedure) {
        return "generated.action." + descriptorActionName(procedure);
    }

    private static List<String> descriptorAffectedConcepts(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            return descriptor.affectedConcepts();
        }
        String explicit = metadataText(procedure.metadata(), "affectedConcepts");
        if (!explicit.isBlank()) {
            return splitMetadataList(explicit);
        }
        String sideEffectConcept = descriptorSideEffectConcept(procedure);
        return sideEffectConcept.isBlank() ? List.of() : List.of(sideEffectConcept);
    }

    private static String descriptorSideEffectConcept(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            return descriptor.sideEffectConcept() == null ? "" : descriptor.sideEffectConcept();
        }
        String explicit = metadataText(procedure.metadata(), "sideEffectConcept");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return inferConceptName(procedure.id());
    }

    private static String descriptorEventNameOnSuccess(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.eventNameOnSuccess() != null && !descriptor.eventNameOnSuccess().isBlank()) {
            return descriptor.eventNameOnSuccess();
        }
        String explicit = metadataText(procedure.metadata(), "eventNameOnSuccess");
        if (!explicit.isBlank()) {
            return explicit;
        }
        return "generated.action." + safeEventToken(descriptorActionName(procedure)) + ".completed";
    }

    private static String descriptorAuditResourceType(TrustedProcedure procedure) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null && descriptor.auditResourceType() != null && !descriptor.auditResourceType().isBlank()) {
            return descriptor.auditResourceType();
        }
        String explicit = metadataText(procedure.metadata(), "auditResourceType");
        return explicit.isBlank() ? "GENERATED_ACTION" : explicit;
    }

    private static String descriptorPolicy(TrustedProcedure procedure, String metadataKey, String defaultValue) {
        CompiledGeneratedActionDescriptorSpec descriptor = procedure.actionDescriptor();
        if (descriptor != null) {
            String value = switch (metadataKey) {
                case "idempotencyPolicy" -> descriptor.idempotencyPolicy();
                case "tracePolicy" -> descriptor.tracePolicy();
                case "correlationPolicy" -> descriptor.correlationPolicy();
                default -> "";
            };
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        String explicit = metadataText(procedure.metadata(), metadataKey);
        return explicit.isBlank() ? defaultValue : explicit;
    }

    private static List<String> splitMetadataList(String raw) {
        List<String> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String item = token == null ? "" : token.trim();
            if (!item.isBlank()) {
                out.add(item);
            }
        }
        return out.isEmpty() ? List.of("GeneratedAction") : List.copyOf(out);
    }

    private static String generatedStringList(List<String> values) {
        List<String> safeValues = values == null ? List.of() : values;
        StringBuilder out = new StringBuilder("List.of(");
        for (int index = 0; index < safeValues.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(quote(safeValues.get(index)));
        }
        out.append(")");
        return out.toString();
    }

    private static String inferConceptName(String actionName) {
        String cleaned = actionName == null ? "" : actionName.trim();
        if (cleaned.isBlank()) {
            return "GeneratedAction";
        }
        for (String prefix : List.of("Create", "Add", "Register", "Upsert", "Update", "Save")) {
            if (cleaned.startsWith(prefix) && cleaned.length() > prefix.length()) {
                return cleaned.substring(prefix.length());
            }
        }
        return cleaned;
    }

    private static String safeEventToken(String actionName) {
        String cleaned = actionName == null ? "" : actionName.trim();
        if (cleaned.isBlank()) {
            return "action";
        }
        return cleaned.replaceAll("([a-z])([A-Z])", "$1-$2")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String controllerSource(List<TrustedProcedure> procedures, List<TrustedPanel> panels, List<TrustedFlow> flows) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.generated.runtime.service.RuntimeContextService;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
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
                    private final GeneratedActionKernelRunner actionKernelRunner;
                    private final GeneratedFlowCodaRunner flowCodaRunner;
                    private final KernelFacade kernelFacade;

                    public GeneratedTrustedSourceRuntimeController(
                            RuntimeContextService runtimeContextService,
                            ConceptGateway conceptGateway,
                            GeneratedActionKernelRunner actionKernelRunner,
                            GeneratedFlowCodaRunner flowCodaRunner,
                            KernelFacade kernelFacade
                    ) {
                        this.runtimeContextService = runtimeContextService;
                        this.conceptGateway = conceptGateway;
                        this.actionKernelRunner = actionKernelRunner;
                        this.flowCodaRunner = flowCodaRunner;
                        this.kernelFacade = kernelFacade;
                    }

                    @PostMapping(value = "/generated/actions/{actionName}/run", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> runAction(
                            @PathVariable String actionName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedActionExecutionResponse response = actionKernelRunner.run(
                                actionName,
                                GeneratedActionExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/procedures/{procedureName}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> invokeProcedure(
                            @PathVariable String procedureName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedActionExecutionResponse response = actionKernelRunner.run(
                                procedureName,
                                GeneratedActionExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @PostMapping(value = "/generated/flows/{flowName}/start", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> startFlow(
                            @PathVariable String flowName,
                            @RequestBody(required = false) Map<String, Object> body,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        GeneratedFlowExecutionResponse response = flowCodaRunner.start(
                                flowName,
                                GeneratedFlowExecutionRequest.from(body),
                                context
                        );
                        return ResponseEntity.status(httpStatusFor(response)).body(response.toMap());
                    }

                    @GetMapping(value = "/generated/actions/executions/{executionId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> actionExecutionEvidence(
                            @PathVariable String executionId,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        return ResponseEntity.ok(kernelFacade.generatedActionEvidenceByExecution(executionId, context));
                    }

                    @GetMapping(value = "/generated/actions/correlations/{correlationId}", produces = MediaType.APPLICATION_JSON_VALUE)
                    public ResponseEntity<Map<String, Object>> actionCorrelationEvidence(
                            @PathVariable String correlationId,
                            HttpServletRequest request
                    ) {
                        ExecutionContext context = runtimeContextService.currentContext(request);
                        return ResponseEntity.ok(kernelFacade.generatedActionEvidenceByCorrelation(correlationId, context));
                    }
                """);

        for (TrustedPanel panel : panels) {
            source.append("    @GetMapping(value = ").append(quote(panel.route())).append(", produces = MediaType.TEXT_HTML_VALUE)\n")
                    .append("    public ResponseEntity<String> panel").append(methodSuffix(panel.id())).append("(HttpServletRequest request) throws Exception {\n")
                    .append("        ExecutionContext context = runtimeContextService.currentContext(request);\n")
                        .append("        int before = 0;\n")
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
                const NPDEV_ACTION_FIELDS = [
                  ['status', 'Status', 'data-npdev-status'],
                  ['executionId', 'Execution ID', 'data-npdev-execution-id'],
                  ['correlationId', 'Correlation ID', 'data-npdev-correlation-id'],
                  ['actionName', 'Action', 'data-npdev-action-name'],
                  ['procedureName', 'Procedure', 'data-npdev-procedure-name'],
                  ['capabilityId', 'Capability', 'data-npdev-capability-id'],
                  ['capabilityDispatchStatus', 'Dispatch', 'data-npdev-dispatch-status'],
                  ['eventStatus', 'Event', 'data-npdev-event-status'],
                  ['traceStatus', 'Trace', 'data-npdev-trace-status'],
                  ['auditStatus', 'Audit', 'data-npdev-audit-status'],
                  ['idempotencyStatus', 'Idempotency', 'data-npdev-idempotency-status'],
                  ['correlationStatus', 'Correlation', 'data-npdev-correlation-status'],
                  ['createdCount', 'Created count', 'data-npdev-created-count'],
                  ['sideEffectCountBefore', 'Side effects before', 'data-npdev-side-effect-before'],
                  ['sideEffectCountAfter', 'Side effects after', 'data-npdev-side-effect-after'],
                  ['message', 'Message', 'data-npdev-message'],
                  ['error', 'Error', 'data-npdev-error']
                ];
                function npdevEscape(value) {
                  return String(value).replace(/[&<>"']/g, function(ch) {
                    return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch];
                  });
                }
                function npdevFieldValue(response, key) {
                  if (!response || typeof response !== 'object' || !Object.prototype.hasOwnProperty.call(response, key)) {
                    return 'unavailable: not returned by runtime';
                  }
                  if (response[key] === null || response[key] === undefined) {
                    return 'unavailable: runtime returned null';
                  }
                  return String(response[key]);
                }
                function npdevRawField(response, key) {
                  if (!response || typeof response !== 'object' || !Object.prototype.hasOwnProperty.call(response, key)) {
                    return '';
                  }
                  const value = response[key];
                  if (value === null || value === undefined) {
                    return '';
                  }
                  return String(value).trim();
                }
                function npdevEvidenceLinksHtml(response) {
                  const executionId = npdevRawField(response, 'executionId');
                  const correlationId = npdevRawField(response, 'correlationId');
                  const links = [];
                  if (executionId) {
                    links.push('<a data-npdev-execution-evidence-link href="/generated/actions/executions/'
                        + encodeURIComponent(executionId)
                        + '">View execution evidence</a>');
                  }
                  if (correlationId) {
                    links.push('<a data-npdev-correlation-evidence-link href="/generated/actions/correlations/'
                        + encodeURIComponent(correlationId)
                        + '">View correlation evidence</a>');
                  }
                  if (links.length === 0) {
                    return '<div class="npdev-action-result__evidence" data-npdev-evidence-link-status>'
                        + 'Evidence link unavailable: executionId/correlationId not returned by runtime'
                        + '</div>';
                  }
                  return '<div class="npdev-action-result__evidence" data-npdev-evidence-link-status>Evidence links available: '
                      + links.join(' ')
                      + '</div>';
                }
                function npdevResultState(response) {
                  const status = npdevFieldValue(response, 'status').toLowerCase();
                  const idempotency = npdevFieldValue(response, 'idempotencyStatus').toLowerCase();
                  const error = npdevFieldValue(response, 'error');
                  if (status.includes('fail') || status.includes('error') || status.includes('reject') || (error && !error.startsWith('unavailable:') && error.trim() !== '')) {
                    return 'error';
                  }
                  if (idempotency.includes('reused') || idempotency.includes('prevented')) {
                    return 'reused';
                  }
                  return 'success';
                }
                window.NPDev.renderActionResultHtml = function(response) {
                  const state = npdevResultState(response);
                  const title = state === 'error'
                    ? 'Action failed'
                    : state === 'reused'
                        ? 'Action reused / duplicate prevented'
                        : 'Action completed';
                  const rows = NPDEV_ACTION_FIELDS.map(function(field) {
                    const value = npdevFieldValue(response, field[0]);
                    return '<div class="npdev-action-result__row" ' + field[2] + '><span class="npdev-action-result__label">'
                        + npdevEscape(field[1])
                        + '</span><span class="npdev-action-result__value">'
                        + npdevEscape(value)
                        + '</span></div>';
                  }).join('');
                  return '<section class="npdev-action-result npdev-action-result--' + state + '" data-npdev-action-result aria-live="polite">'
                      + '<h2>' + npdevEscape(title) + '</h2>'
                      + rows
                      + npdevEvidenceLinksHtml(response)
                      + '</section>';
                };
                function npdevDefaultResultContainer() {
                  let container = document.querySelector('[data-npdev-action-result-root]');
                  if (!container) {
                    container = document.createElement('div');
                    container.setAttribute('data-npdev-action-result-root', '');
                    document.body.appendChild(container);
                  }
                  return container;
                }
                window.NPDev.renderActionResult = function(container, response) {
                  const target = container || npdevDefaultResultContainer();
                  target.innerHTML = window.NPDev.renderActionResultHtml(response || {});
                  return target;
                };
                async function npdevParseResponse(response) {
                  const contentType = response.headers.get('content-type') || '';
                  if (contentType.includes('application/json')) {
                    return await response.json();
                  }
                  const text = await response.text();
                  return { status: response.ok ? 'ok' : 'error', error: text || ('HTTP ' + response.status) };
                }
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
                  const body = await npdevParseResponse(response);
                  window.NPDev.renderActionResult(null, body);
                  if (!response.ok) {
                    const error = new Error(body.message || body.reason || body.error || 'trusted procedure failed');
                    error.responseBody = body;
                    error.httpStatus = response.status;
                    throw error;
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

                    private static HttpStatus httpStatusFor(GeneratedActionExecutionResponse response) {
                        if (response == null) {
                            return HttpStatus.INTERNAL_SERVER_ERROR;
                        }
                        if ("ok".equalsIgnoreCase(response.status())) {
                            return HttpStatus.OK;
                        }
                        if ("rejected".equalsIgnoreCase(response.status())) {
                            if ("unknown-action".equalsIgnoreCase(response.error())) {
                                return HttpStatus.NOT_FOUND;
                            }
                            return HttpStatus.FORBIDDEN;
                        }
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    private static HttpStatus httpStatusFor(GeneratedFlowExecutionResponse response) {
                        if (response == null) {
                            return HttpStatus.INTERNAL_SERVER_ERROR;
                        }
                        if ("ok".equalsIgnoreCase(response.status())) {
                            return HttpStatus.OK;
                        }
                        if ("rejected".equalsIgnoreCase(response.status())) {
                            if ("unknown-flow".equalsIgnoreCase(response.error())) {
                                return HttpStatus.NOT_FOUND;
                            }
                            return HttpStatus.FORBIDDEN;
                        }
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
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
            CompiledGeneratedActionDescriptorSpec actionDescriptor,
            Map<String, Object> metadata,
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

    private record TrustedFlow(
            String flowName,
            String actionName
    ) {
    }

    private record PanelAssets(
            String html,
            String css,
            String js
    ) {
    }
}
