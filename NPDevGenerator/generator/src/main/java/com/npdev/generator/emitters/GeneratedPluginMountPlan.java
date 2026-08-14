package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirement;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirementGraph;
import com.npdev.dsl.v1.compiled.CompiledPluginRequirementGraphBuilder;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GeneratedPluginMountPlan {

    public static final String GENERIC_MOUNTED_RUNTIME_REF = "genericMountedCapabilityHandler";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final List<String> RESERVED_CAPABILITY_NAMES = List.of(
            "persistence",
            "notification",
            "webhook",
            "eventbus"
    );

    private static final List<String> RESERVED_CAPABILITY_TYPES = List.of(
            "persistencecapability",
            "notificationcapability",
            "webhookcapability",
            "apicapability",
            "messagingcapability",
            "eventpublicationcapability",
            "eventbuscapability"
    );

    private final List<Mount> mounts;

    private GeneratedPluginMountPlan(List<Mount> mounts) {
        this.mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
    }

    public static GeneratedPluginMountPlan empty() {
        return new GeneratedPluginMountPlan(List.of());
    }

    public static GeneratedPluginMountPlan fromModelSource(Path modelSourcePath) {
        return fromModelSource(null, modelSourcePath);
    }

    public static GeneratedPluginMountPlan fromModelSource(ResolvedModelSource resolvedModelSource, Path modelSourcePath) {
        if (modelSourcePath == null || !Files.exists(modelSourcePath)) {
            if (resolvedModelSource == null) {
                return empty();
            }
        }
        try {
            ModelAst modelAst = resolvedModelSource == null
                    ? new JsonModelParser().parse(modelSourcePath)
                    : new JsonModelParser().parse(resolvedModelSource);
            CompiledPluginRequirementGraph graph = new CompiledPluginRequirementGraphBuilder().build(modelAst);
            Path artifactRoot = resolvedModelSource == null
                    ? modelSourcePath.toAbsolutePath().normalize().getParent()
                    : resolvedModelSource.canonicalRootDirectory();
            Map<String, Mount> byKey = new LinkedHashMap<>();
            for (CompiledPluginRequirement requirement : graph.getRequirements()) {
                if (!isMountCandidate(requirement)) {
                    continue;
                }
                Mount mount = Mount.from(requirement, artifactRoot);
                byKey.putIfAbsent(mount.deduplicationKey(), mount);
            }
            List<Mount> sorted = new ArrayList<>(byKey.values());
            sorted.sort(Mount.ORDERING);
            return new GeneratedPluginMountPlan(sorted);
        } catch (Exception exception) {
            throw new RuntimeException("Failed building generated plugin mount plan from model source: " + modelSourcePath, exception);
        }
    }

    public boolean isEmpty() {
        return mounts.isEmpty();
    }

    public List<Mount> mounts() {
        return mounts;
    }

    /**
     * R10 correction, found by a real boot (not assumed): JAVA_CONTROLLER mounts DO need a plugin
     * manifest contribution, same as every other mount kind. {@code NpdevCapabilityBindingConfig
     * .capabilityRegistry()} EAGERLY resolves EVERY {@code compiledModel.getBindings()} entry at
     * boot (not lazily, only when a flow calls it, as the JAVA_SOURCE precedent's own comments
     * elsewhere in this file assume) via {@code CapabilityAdapterResolver.resolve()}, which requires
     * {@code RuntimePluginAdapterRegistry} to have SOME contribution for {capability, adapterId} --
     * with a blank operation, since a controller-bound capability is never called by name, the
     * registry's own fallback (blank operation -> ignore the exact operation index, match on
     * {capability, adapterId} alone) is exactly what a controller mount needs. Omitting the
     * contribution entirely (an earlier version of this method did) fails EVERY app with a plugin
     * controller at boot with "Adapter 'plugin:java-controller' for capability '...' operation
     * '&lt;binding&gt;' is not declared in active plugin manifest" -- confirmed live via
     * run-r10-plugin-controller-proof.py. {@link Mount#runtimeRef()} already resolves a
     * JAVA_CONTROLLER mount to {@link #GENERIC_MOUNTED_RUNTIME_REF} (the same fallback every other
     * unrecognized {@code plugin:*} adapter uses) because {@code javaSource} is null for this kind --
     * so nothing else has to change; the manifest contribution is realized against a handler nothing
     * ever actually calls (no flow references a mounted controller's capability), which is harmless.
     */
    public List<PackageGroup> packageGroups() {
        Map<String, List<Mount>> byCapabilityAdapter = new LinkedHashMap<>();
        for (Mount mount : mounts) {
            byCapabilityAdapter.computeIfAbsent(mount.packageGroupKey(), ignored -> new ArrayList<>()).add(mount);
        }
        List<PackageGroup> groups = new ArrayList<>();
        for (List<Mount> groupMounts : byCapabilityAdapter.values()) {
            groupMounts.sort(Mount.ORDERING);
            groups.add(new PackageGroup(groupMounts.get(0).normalizedCapability(), groupMounts.get(0).normalizedAdapter(), groupMounts));
        }
        groups.sort(PackageGroup.ORDERING);
        return groups;
    }

    public List<JavaSourcePackageGroup> javaSourcePackageGroups() {
        return packageGroups().stream()
                .filter(group -> group.representative().mountKind() == MountKind.JAVA_SOURCE)
                .map(group -> new JavaSourcePackageGroup(group.representative().javaSource(), group.mounts()))
                .toList();
    }

    /** R10: every plugin:java-controller mount, in the same deterministic order as {@link #mounts()}.
     *  Deliberately NOT grouped by {@link #packageGroups()} -- see that method's javadoc. */
    public List<Mount> javaControllerMounts() {
        return mounts.stream().filter(mount -> mount.mountKind() == MountKind.JAVA_CONTROLLER).toList();
    }

    public static boolean isReservedCapability(String capabilityName, String capabilityType) {
        String normalizedName = normalize(capabilityName);
        String normalizedType = normalize(capabilityType);
        return RESERVED_CAPABILITY_NAMES.contains(normalizedName)
                || RESERVED_CAPABILITY_TYPES.contains(normalizedType);
    }

    private static boolean isMountCandidate(CompiledPluginRequirement requirement) {
        return requirement.externalCandidate()
                && !isBlank(requirement.capabilityName())
                && !isBlank(requirement.operationName())
                && !isBlank(requirement.boundAdapter())
                && requirement.boundAdapter().trim().toLowerCase(Locale.ROOT).startsWith("plugin:")
                && !isReservedCapability(requirement.capabilityName(), requirement.capabilityType());
    }

    static String normalizedIdPart(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized.isBlank() ? "unnamed" : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static MountKind mountKind(String adapterId) {
        String normalized = normalize(adapterId);
        if ("plugin:java-source".equals(normalized)) {
            return MountKind.JAVA_SOURCE;
        }
        if ("plugin:java-controller".equals(normalized)) {
            return MountKind.JAVA_CONTROLLER;
        }
        return MountKind.GENERIC_MOUNTED;
    }

    /**
     * R10: the ONE package prefix a mounted plugin controller's source is allowed to declare.
     * {@code FinalExecApplication}'s {@code @ComponentScan} only scans {@code com.finalexec} (the
     * platform's own, allowlist-gated surface) and {@code com.npdev.generated} (generated + mounted
     * content) -- so a controller outside either is never a Spring bean at all, silently dead code
     * rather than a security hole. Forcing it specifically under {@code .plugin.} (not the sibling
     * {@code .controllers.}/{@code .services.} packages the generator itself emits into) is what lets
     * the runtime-side guard (PluginControllerSecurityConfig, the 4th enforcement point) tell
     * "a plugin controller that must have a security-manifest entry" apart from "any other generated
     * bean" using nothing but the package name.
     */
    static final String PLUGIN_CONTROLLER_PACKAGE_PREFIX = "com.npdev.generated.plugin.";

    /** R10: every plugin:java-controller mount is reserved to this URL prefix, so it can never
     *  collide with a platform-owned {@code /api/*} route (present or future) outside it. */
    static final String PLUGIN_CONTROLLER_BASE_PATH_PREFIX = "/api/plugins/";

    private static JavaSourceDescriptor loadJavaSourceDescriptor(CompiledPluginRequirement requirement, Path artifactRoot) {
        if (artifactRoot == null) {
            throw new IllegalStateException("plugin:java-source capability '" + requirement.capabilityName()
                    + "' requires a model source parent for capability.plugin.json discovery.");
        }
        Path descriptorPath = artifactRoot
                .resolve("capabilities")
                .resolve(requirement.capabilityName())
                .resolve("capability.plugin.json")
                .normalize();
        if (!descriptorPath.startsWith(artifactRoot) || !Files.isRegularFile(descriptorPath)) {
            throw new IllegalStateException("plugin:java-source capability '" + requirement.capabilityName()
                    + "' requires descriptor: capabilities/" + requirement.capabilityName() + "/capability.plugin.json");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(descriptorPath.toFile());
            String capability = requiredText(root, "capability", descriptorPath);
            String capabilityType = text(root, "capabilityType");
            String adapterId = requiredText(root, "adapterId", descriptorPath);
            String runtimeRef = requiredText(root, "runtimeRef", descriptorPath);
            String packageId = requiredText(root, "packageId", descriptorPath);
            String pluginId = requiredText(root, "pluginId", descriptorPath);
            String displayName = firstNonBlank(text(root, "displayName"), "User " + capability + " Java source plugin");
            String version = firstNonBlank(text(root, "version"), "1.0.0");

            JsonNode implementation = root.path("implementation");
            if (!"javasource".equals(normalize(implementation.path("kind").asText()))) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " must declare implementation.kind = javaSource");
            }
            String sourceRoot = requiredText(implementation, "sourceRoot", descriptorPath);
            String mainClass = requiredText(implementation, "mainClass", descriptorPath);
            Path resolvedSourceRoot = artifactRoot.resolve(sourceRoot.replace('/', java.io.File.separatorChar)).normalize();
            if (!resolvedSourceRoot.startsWith(artifactRoot) || !Files.isDirectory(resolvedSourceRoot)) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " points to missing sourceRoot: " + sourceRoot);
            }
            try (java.util.stream.Stream<Path> stream = Files.walk(resolvedSourceRoot)) {
                if (stream.noneMatch(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))) {
                    throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                            + " sourceRoot has no Java source files: " + sourceRoot);
                }
            }
            Path mainClassSource = resolvedSourceRoot.resolve(mainClass.replace('.', '/') + ".java").normalize();
            if (!mainClassSource.startsWith(resolvedSourceRoot) || !Files.isRegularFile(mainClassSource)) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " points to missing mainClass source: " + mainClass + " expected at " + sourceRoot + "/"
                        + mainClass.replace('.', '/') + ".java");
            }

            Map<String, String> methodByOperation = new LinkedHashMap<>();
            for (JsonNode binding : root.path("operationBindings")) {
                String operation = requiredText(binding, "operation", descriptorPath);
                String method = requiredText(binding, "method", descriptorPath);
                methodByOperation.put(normalize(operation), method);
            }
            String requiredOperation = normalize(requirement.operationName());
            if (!methodByOperation.containsKey(requiredOperation)) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " does not declare operation binding for " + requirement.capabilityName() + "."
                        + requirement.operationName());
            }
            if (!normalize(capability).equals(normalize(requirement.capabilityName()))) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " capability '" + capability + "' does not match model capability '"
                        + requirement.capabilityName() + "'");
            }
            if (!normalize(adapterId).equals(normalize(requirement.boundAdapter()))) {
                throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                        + " adapterId '" + adapterId + "' does not match model binding adapter '"
                        + requirement.boundAdapter() + "'");
            }
            return new JavaSourceDescriptor(
                    descriptorPath,
                    artifactRoot,
                    packageId,
                    pluginId,
                    displayName,
                    version,
                    capability,
                    capabilityType,
                    adapterId,
                    runtimeRef,
                    sourceRoot.replace('\\', '/'),
                    resolvedSourceRoot,
                    mainClass,
                    Map.copyOf(methodByOperation)
            );
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading plugin:java-source descriptor: " + descriptorPath, exception);
        }
    }

    /**
     * R10: mirrors {@link #loadJavaSourceDescriptor} as closely as the shape difference allows --
     * same {@code capabilities/<name>/capability.plugin.json} descriptor location, same
     * artifact-local sourceRoot copy-at-generation-time mechanism -- but a raw {@code @RestController}
     * has no {@code operationBindings} (Spring routes it by annotation, not by capability-dispatch
     * method lookup) and instead declares a {@code mount} block: {@code basePath} (the URL prefix
     * Spring will actually serve it under) and {@code security.minimumRole} (D9: enforced by a
     * generated wrapper at request time, not merely validated here and trusted -- see
     * {@code PluginControllerSecurityConfig} in NPDevRuntimeHost, the 4th enforcement point). Both are
     * REQUIRED: a plugin controller with no declared minimumRole fails generation outright rather than
     * silently mounting unguarded, which is the exact "declared-only is worse than none" trap D9 was
     * written to close off.
     *
     * <p>npdev-plugin-controller-security-enforcement: twin-pair token
     * (scripts/quality/twin-pair-registry.json) binding this descriptor loader to
     * {@code RuntimeApiEmitter} (writes the security manifest from these validated fields),
     * NPDevRuntimeHost's {@code PluginControllerSecurityConfig} (reads that manifest and enforces
     * minimumRole at request time -- the 4th runtime-supported-controller enforcement point), and
     * {@code run-r10-plugin-controller-proof.py} (the live proof this mechanism is verified by).
     */
    private static JavaControllerDescriptor loadJavaControllerDescriptor(CompiledPluginRequirement requirement, Path artifactRoot) {
        if (artifactRoot == null) {
            throw new IllegalStateException("plugin:java-controller capability '" + requirement.capabilityName()
                    + "' requires a model source parent for capability.plugin.json discovery.");
        }
        Path descriptorPath = artifactRoot
                .resolve("capabilities")
                .resolve(requirement.capabilityName())
                .resolve("capability.plugin.json")
                .normalize();
        if (!descriptorPath.startsWith(artifactRoot) || !Files.isRegularFile(descriptorPath)) {
            throw new IllegalStateException("plugin:java-controller capability '" + requirement.capabilityName()
                    + "' requires descriptor: capabilities/" + requirement.capabilityName() + "/capability.plugin.json");
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(descriptorPath.toFile());
            String capability = requiredText(root, "capability", descriptorPath);
            String capabilityType = text(root, "capabilityType");
            String adapterId = requiredText(root, "adapterId", descriptorPath);
            String packageId = requiredText(root, "packageId", descriptorPath);
            String pluginId = requiredText(root, "pluginId", descriptorPath);
            String displayName = firstNonBlank(text(root, "displayName"), "User " + capability + " Java controller plugin");
            String version = firstNonBlank(text(root, "version"), "1.0.0");

            JsonNode implementation = root.path("implementation");
            if (!"javacontroller".equals(normalize(implementation.path("kind").asText()))) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " must declare implementation.kind = javaController");
            }
            String sourceRoot = requiredText(implementation, "sourceRoot", descriptorPath);
            String controllerClass = requiredText(implementation, "controllerClass", descriptorPath);
            if (!normalize(controllerClass).startsWith(normalize(PLUGIN_CONTROLLER_PACKAGE_PREFIX))) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " controllerClass '" + controllerClass + "' must be declared under package "
                        + PLUGIN_CONTROLLER_PACKAGE_PREFIX + " (FinalExecApplication only component-scans "
                        + "com.finalexec and com.npdev.generated; the security guard only recognizes this "
                        + "reserved subpackage as a mounted plugin controller)");
            }
            Path resolvedSourceRoot = artifactRoot.resolve(sourceRoot.replace('/', java.io.File.separatorChar)).normalize();
            if (!resolvedSourceRoot.startsWith(artifactRoot) || !Files.isDirectory(resolvedSourceRoot)) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " points to missing sourceRoot: " + sourceRoot);
            }
            Path controllerClassSource = resolvedSourceRoot.resolve(controllerClass.replace('.', '/') + ".java").normalize();
            if (!controllerClassSource.startsWith(resolvedSourceRoot) || !Files.isRegularFile(controllerClassSource)) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " points to missing controllerClass source: " + controllerClass + " expected at "
                        + sourceRoot + "/" + controllerClass.replace('.', '/') + ".java");
            }

            JsonNode mount = root.path("mount");
            String basePath = requiredText(mount, "basePath", descriptorPath);
            if (!basePath.startsWith(PLUGIN_CONTROLLER_BASE_PATH_PREFIX)) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " mount.basePath '" + basePath + "' must start with " + PLUGIN_CONTROLLER_BASE_PATH_PREFIX
                        + " -- every plugin controller is reserved to this URL prefix so it can never collide "
                        + "with a platform-owned route");
            }
            // Adversarial-review finding (independent security review of the R10 PR): declaring a
            // narrow, role-gated basePath here proved nothing about what routes the controller
            // actually registers with Spring -- nothing before this line ever opened the .java file.
            // A SECOND @GetMapping in the same class, mapped outside the declared basePath, compiled
            // cleanly, mounted cleanly, and served completely unguarded: PluginControllerSecurityConfig's
            // interceptor only ever registers interceptPathPatterns(basePath + "/**") from the MANIFEST,
            // never from the controller's own annotations, and its fail-closed bean guard only checks
            // "does a manifest entry exist for this class name," never "does every route this class
            // serves fall inside that entry's basePath." Exactly the "declared reads as a guarantee,
            // nothing enforces it" trap D9 exists to close, recreated one level below where D9 closed
            // it. Closed here, at generation time, by refusing to generate an app whose controller
            // declares a route outside its own basePath -- the strictly stronger guarantee versus a
            // boot-time backstop, since a generated app can then never have the vulnerability at all.
            validateControllerRoutesWithinBasePath(controllerClassSource, basePath, descriptorPath);
            JsonNode security = mount.path("security");
            String minimumRole = requiredText(security, "minimumRole", descriptorPath);

            if (!normalize(capability).equals(normalize(requirement.capabilityName()))) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " capability '" + capability + "' does not match model capability '"
                        + requirement.capabilityName() + "'");
            }
            if (!normalize(adapterId).equals(normalize(requirement.boundAdapter()))) {
                throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                        + " adapterId '" + adapterId + "' does not match model binding adapter '"
                        + requirement.boundAdapter() + "'");
            }
            return new JavaControllerDescriptor(
                    descriptorPath,
                    artifactRoot,
                    packageId,
                    pluginId,
                    displayName,
                    version,
                    capability,
                    capabilityType,
                    adapterId,
                    sourceRoot.replace('\\', '/'),
                    resolvedSourceRoot,
                    controllerClass,
                    basePath,
                    minimumRole.toUpperCase(Locale.ROOT)
            );
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading plugin:java-controller descriptor: " + descriptorPath, exception);
        }
    }

    /**
     * Finds the class declaration that splits "class-level annotations" (before it) from "method
     * bodies" (after it) -- deliberately permissive about modifiers, since the only thing that
     * matters is where the split falls, not validating the declaration itself. Assumes one top-level
     * class per mounted-controller source file, which the descriptor's own controllerClass -> file
     * path convention already requires.
     */
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "(?m)^[ \\t]*(?:public\\s+|final\\s+|abstract\\s+|static\\s+)*class\\s+\\w+");

    /** Every Spring annotation that can register an HTTP route, class-level or method-level. */
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b\\s*(\\([^)]*\\))?");

    /** Within a mapping annotation's parenthesized args, the path is either the bare positional
     *  value or an explicit value=/path= attribute -- never consumes=/produces=/params=/headers=,
     *  which are also string literals and must NOT be mistaken for a route path. */
    private static final Pattern VALUE_OR_PATH_ATTRIBUTE = Pattern.compile(
            "(?:value|path)\\s*=\\s*(\\{[^}]*\\}|\"[^\"]*\")");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    /**
     * R10 (adversarial-review finding, closed the session it was raised in): a declared basePath and
     * minimumRole meant nothing if the controller's ACTUAL Spring routes could sit outside that
     * basePath -- generation and the runtime fail-closed guard both only ever checked "does a
     * manifest entry exist for this class name," never "does every route this class serves fall
     * inside that entry's basePath." This is a regex scan, not a full Java/AST parser (mirroring how
     * loadJavaSourceDescriptor already validates operationBindings against declared operations
     * without parsing the file as Java) -- adequate for catching the real, concrete risk (an honest
     * mistake, or a second route quietly added later) without needing a compiler front end. It
     * fails CLOSED on anything it cannot confidently place inside basePath, which is the correct
     * default for a security check: an ambiguous route is refused, never silently accepted.
     */
    private static void validateControllerRoutesWithinBasePath(Path controllerClassSource, String basePath, Path descriptorPath) {
        String source;
        try {
            source = Files.readString(controllerClassSource, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading plugin:java-controller source for route validation: "
                    + controllerClassSource, exception);
        }

        Matcher classSplit = CLASS_DECLARATION.matcher(source);
        int classBodyStart = classSplit.find() ? classSplit.end() : 0;
        String classLevelRegion = source.substring(0, classBodyStart);
        String classBodyRegion = source.substring(classBodyStart);

        List<String> classBasePaths = List.of("");
        Matcher classLevelMapping = MAPPING_ANNOTATION.matcher(classLevelRegion);
        while (classLevelMapping.find()) {
            if ("RequestMapping".equals(classLevelMapping.group(1))) {
                classBasePaths = extractAnnotationPaths(classLevelMapping.group(2));
            }
        }

        List<String> offendingRoutes = new ArrayList<>();
        Matcher methodMapping = MAPPING_ANNOTATION.matcher(classBodyRegion);
        while (methodMapping.find()) {
            String annotationName = methodMapping.group(1);
            for (String methodPath : extractAnnotationPaths(methodMapping.group(2))) {
                for (String classPath : classBasePaths) {
                    String fullPath = joinRoutePaths(classPath, methodPath);
                    if (!isWithinBasePath(fullPath, basePath)) {
                        offendingRoutes.add(fullPath + " (@" + annotationName + ")");
                    }
                }
            }
        }

        if (!offendingRoutes.isEmpty()) {
            throw new IllegalStateException("plugin:java-controller descriptor " + descriptorPath
                    + " declares mount.basePath '" + basePath + "', but " + controllerClassSource
                    + " registers route(s) outside it: " + offendingRoutes
                    + " -- every route the controller serves must fall inside its declared basePath, "
                    + "or minimumRole's enforcement (the interceptor guards basePath + \"/**\", nothing "
                    + "else) would not cover it. Move the route under " + basePath
                    + " or split it into its own plugin:java-controller mount with its own basePath.");
        }
    }

    /**
     * Extracts the route path(s) an annotation declares from its raw parenthesized argument text
     * (including the surrounding parens, or null for a bare annotation with no args at all, e.g.
     * {@code @GetMapping}). Deliberately conservative: a named {@code value=}/{@code path=} attribute
     * wins if present (so {@code consumes=}/{@code produces=}/etc. string literals are never mistaken
     * for a route); otherwise, ONLY if the args contain no {@code =} at all (i.e. a purely positional
     * value, {@code @GetMapping("/ping")}) are its string literals treated as paths; anything else
     * (e.g. {@code @RequestMapping(method = RequestMethod.GET)}, no path attribute at all) defaults
     * to a single empty path, meaning "exactly the class-level basePath, or root if there is none."
     */
    private static List<String> extractAnnotationPaths(String rawArgsWithParens) {
        if (rawArgsWithParens == null) {
            return List.of("");
        }
        String argsText = rawArgsWithParens.trim();
        if (argsText.startsWith("(")) {
            argsText = argsText.substring(1);
        }
        if (argsText.endsWith(")")) {
            argsText = argsText.substring(0, argsText.length() - 1);
        }
        argsText = argsText.trim();
        if (argsText.isEmpty()) {
            return List.of("");
        }

        Matcher namedAttribute = VALUE_OR_PATH_ATTRIBUTE.matcher(argsText);
        if (namedAttribute.find()) {
            List<String> paths = extractStringLiterals(namedAttribute.group(1));
            return paths.isEmpty() ? List.of("") : paths;
        }
        if (!argsText.contains("=")) {
            List<String> paths = extractStringLiterals(argsText);
            if (!paths.isEmpty()) {
                return paths;
            }
        }
        return List.of("");
    }

    private static List<String> extractStringLiterals(String text) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(text);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    /** Mirrors Spring's own class-path + method-path combination: a blank sub-path means "exactly
     *  the base," a blank base means "the sub-path is absolute on its own." */
    private static String joinRoutePaths(String basePath, String subPath) {
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

    /** Same coverage semantics PluginControllerSecurityConfig's interceptor uses
     *  ({@code addPathPatterns(basePath + "/**")}): the base itself, or anything one level (or
     *  deeper) under it. */
    private static boolean isWithinBasePath(String path, String basePath) {
        return path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String requiredText(JsonNode node, String fieldName, Path descriptorPath) {
        String value = text(node, fieldName);
        if (value.isBlank()) {
            throw new IllegalStateException("plugin:java-source descriptor " + descriptorPath
                    + " is missing required field: " + fieldName);
        }
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first.trim() : second;
    }

    public enum MountKind {
        GENERIC_MOUNTED,
        JAVA_SOURCE,
        JAVA_CONTROLLER
    }

    public record Mount(
            String capability,
            String capabilityType,
            String operation,
            String adapterId,
            String flow,
            String step,
            String normalizedCapability,
            String normalizedAdapter,
            MountKind mountKind,
            JavaSourceDescriptor javaSource,
            JavaControllerDescriptor javaController
    ) {

        private static final Comparator<Mount> ORDERING = Comparator
                .comparing(Mount::capability, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Mount::operation, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Mount::adapterId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Mount::flow, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Mount::step, String.CASE_INSENSITIVE_ORDER);

        static Mount from(CompiledPluginRequirement requirement, Path artifactRoot) {
            MountKind kind = GeneratedPluginMountPlan.mountKind(requirement.boundAdapter());
            JavaSourceDescriptor javaSource = kind == MountKind.JAVA_SOURCE
                    ? loadJavaSourceDescriptor(requirement, artifactRoot)
                    : null;
            JavaControllerDescriptor javaController = kind == MountKind.JAVA_CONTROLLER
                    ? loadJavaControllerDescriptor(requirement, artifactRoot)
                    : null;
            return new Mount(
                    requirement.capabilityName(),
                    requirement.capabilityType(),
                    requirement.operationName(),
                    requirement.boundAdapter(),
                    requirement.flowName(),
                    requirement.stepName(),
                    normalizedIdPart(requirement.capabilityName()),
                    normalizedIdPart(requirement.boundAdapter()),
                    kind,
                    javaSource,
                    javaController
            );
        }

        public String pluginId() {
            if (javaSource != null) {
                return javaSource.pluginId();
            }
            if (javaController != null) {
                return javaController.pluginId();
            }
            return "generated-" + normalizedCapability + "-" + normalizedAdapter + "-plugin";
        }

        public String packageId() {
            if (javaSource != null) {
                return javaSource.packageId();
            }
            if (javaController != null) {
                return javaController.packageId();
            }
            return "generated-" + normalizedCapability + "-" + normalizedAdapter + "-package";
        }

        public String packageFileName() {
            return packageId() + ".package.json";
        }

        public String bindingKey() {
            return capability + "." + operation + "." + adapterId;
        }

        String deduplicationKey() {
            return normalize(capability) + "|" + normalize(operation) + "|" + normalize(adapterId);
        }

        String packageGroupKey() {
            return normalize(capability) + "|" + normalize(adapterId);
        }

        public String runtimeRef() {
            return javaSource == null ? GENERIC_MOUNTED_RUNTIME_REF : javaSource.runtimeRef();
        }

        public String displayName() {
            if (javaSource != null) {
                return javaSource.displayName();
            }
            if (javaController != null) {
                return javaController.displayName();
            }
            return "Generated " + capability + " plugin";
        }

        public String version() {
            if (javaSource != null) {
                return javaSource.version();
            }
            if (javaController != null) {
                return javaController.version();
            }
            return "1.0.0";
        }

        public String methodName() {
            return javaSource == null ? operation : javaSource.methodForOperation(operation);
        }

        /** R10: the URL prefix this mounted controller is served under, or "" for any other mount kind. */
        public String controllerBasePath() {
            return javaController == null ? "" : javaController.basePath();
        }

        /** R10: the role D9 requires the generated security wrapper to enforce before delegating,
         *  or "" for any other mount kind. */
        public String controllerMinimumRole() {
            return javaController == null ? "" : javaController.minimumRole();
        }

        /** R10: the mounted controller's fully-qualified class name, or "" for any other mount kind. */
        public String controllerClassName() {
            return javaController == null ? "" : javaController.controllerClass();
        }

        /** R10: the mounted controller's simple class name -- what
         *  PluginControllerSecurityConfig matches Spring bean class names against. */
        public String controllerSimpleClassName() {
            String fullyQualified = controllerClassName();
            int lastDot = fullyQualified.lastIndexOf('.');
            return lastDot < 0 ? fullyQualified : fullyQualified.substring(lastDot + 1);
        }
    }

    public record PackageGroup(
            String normalizedCapability,
            String normalizedAdapter,
            List<Mount> mounts
    ) {

        private static final Comparator<PackageGroup> ORDERING = Comparator
                .comparing(PackageGroup::normalizedCapability, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PackageGroup::normalizedAdapter, String.CASE_INSENSITIVE_ORDER);

        public PackageGroup {
            mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        }

        public Mount representative() {
            return mounts.get(0);
        }
    }

    public record JavaSourceDescriptor(
            Path descriptorPath,
            Path artifactRoot,
            String packageId,
            String pluginId,
            String displayName,
            String version,
            String capability,
            String capabilityType,
            String adapterId,
            String runtimeRef,
            String sourceRoot,
            Path resolvedSourceRoot,
            String mainClass,
            Map<String, String> methodByOperation
    ) {

        public String methodForOperation(String operation) {
            return methodByOperation.get(normalize(operation));
        }
    }

    /** R10: the plugin:java-controller sibling of {@link JavaSourceDescriptor} -- same descriptor
     *  file convention, but a {@code mount} block (basePath + security.minimumRole) instead of
     *  {@code runtimeRef}/{@code operationBindings}, since a raw controller is routed by Spring
     *  annotations, never capability-dispatched. */
    public record JavaControllerDescriptor(
            Path descriptorPath,
            Path artifactRoot,
            String packageId,
            String pluginId,
            String displayName,
            String version,
            String capability,
            String capabilityType,
            String adapterId,
            String sourceRoot,
            Path resolvedSourceRoot,
            String controllerClass,
            String basePath,
            String minimumRole
    ) {
    }

    public record JavaSourcePackageGroup(
            JavaSourceDescriptor descriptor,
            List<Mount> mounts
    ) {

        public JavaSourcePackageGroup {
            mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        }
    }
}
