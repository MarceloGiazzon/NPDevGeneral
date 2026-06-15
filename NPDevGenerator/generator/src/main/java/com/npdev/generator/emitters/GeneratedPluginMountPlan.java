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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
        return MountKind.GENERIC_MOUNTED;
    }

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
        JAVA_SOURCE
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
            JavaSourceDescriptor javaSource
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
                    javaSource
            );
        }

        public String pluginId() {
            if (javaSource != null) {
                return javaSource.pluginId();
            }
            return "generated-" + normalizedCapability + "-" + normalizedAdapter + "-plugin";
        }

        public String packageId() {
            if (javaSource != null) {
                return javaSource.packageId();
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
            return javaSource == null ? "Generated " + capability + " plugin" : javaSource.displayName();
        }

        public String version() {
            return javaSource == null ? "1.0.0" : javaSource.version();
        }

        public String methodName() {
            return javaSource == null ? operation : javaSource.methodForOperation(operation);
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

    public record JavaSourcePackageGroup(
            JavaSourceDescriptor descriptor,
            List<Mount> mounts
    ) {

        public JavaSourcePackageGroup {
            mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        }
    }
}
