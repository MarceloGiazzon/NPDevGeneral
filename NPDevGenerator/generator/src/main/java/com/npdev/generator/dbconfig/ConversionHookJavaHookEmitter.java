package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledConversion;
import com.npdev.generator.emitters.PluginJavaSourcePolicy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * B1 (REAL_LIFT_PLAN_2026-09-03, B13): admits and registers a {@code conversions[].javaHook} exactly
 * as a {@code plugin:java-source} mount is admitted -- same AST/bytecode escape-detection ({@link
 * PluginJavaSourcePolicy}, the boot-time bytecode gate), same plugin-runtime manifests {@code
 * RuntimeApiEmitter} already writes for capability-bound mounts, same {@code
 * ManifestDrivenJavaSourcePluginHandler} dispatch on the child side -- reused verbatim, not
 * reimplemented, since a javaHook's author contract (one {@code Map<String,Object>} in, one out) is
 * the exact shape that handler already reflects into. No new isolation machinery: this class is only
 * the adapter between {@code conversions[]}'s inline {@code javaHook} shape (no {@code
 * capabilities[]}/{@code bindings[]} pair, unlike every other mount kind) and those SAME generated
 * artifacts, so {@code PluginIpcChildProcessPool}'s existing null-when-empty bean condition ({@code
 * NpdevPluginConfig}, keyed on {@code java-source-runtime-refs.json} being non-empty) "just works" for
 * an app whose only plugin surface is one javaHook -- no separate pool, no separate manifest format,
 * no separate bean.
 *
 * <p>The child process never gets a {@code DataSource}: {@link JavaMigrationHookRunner} (runtime side)
 * performs the actual row write itself, on the host, from the value the child's method returns -- the
 * write never crosses back INTO the child, so there is nothing for the child to call back for. This
 * is a narrower mechanism than a general {@code plugin:java-source} mount ever needs, which is why
 * only ONE adapter row is registered per javaHook (the invoke contribution), not a declared-callback
 * pair.
 *
 * <p>Runs from {@link ConversionHookEmitter}, which runs LAST in the generator pipeline (after {@code
 * RuntimeApiEmitter} has already written every artifact this class appends to) -- a
 * read-modify-write on each, not a first write, and each is created fresh only if an app's ONLY
 * plugin surface is a javaHook (no other mount ever ran first).
 *
 * <p><b>Untracked twin:</b> the manifest shapes here (plugin-manifest.json's {@code plugins[].
 * adapters[]}, java-source-runtime-refs.json's {@code javaSourcePlugins[]}, plugin-owned-classes.txt)
 * mirror {@code RuntimeApiEmitter}'s own emission of the exact same files -- {@code
 * check-schema-mirror-consistency.py} does not cover this pair (it is file *content* produced by two
 * emitters, not two schema files). A shape change on either side must be mirrored on the other by
 * hand.
 */
final class ConversionHookJavaHookEmitter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> MANIFEST_FILE_NAMES = List.of(
            "default.plugin-manifest.json", "dev.plugin-manifest.json", "warning.plugin-manifest.json");

    private ConversionHookJavaHookEmitter() {
    }

    static void admitAndRegister(CompiledConversion conversion, Path definitionDir, Path outRoot) throws IOException {
        CompiledConversion.CompiledJavaHook javaHook = conversion.javaHook();
        String id = conversion.id();
        if (definitionDir == null) {
            throw new IllegalStateException("conversion '" + id
                    + "' declares javaHook but no model source parent is available to resolve its source root.");
        }
        Path sourceRoot = definitionDir.resolve(javaHook.source().replace('/', File.separatorChar)).normalize();
        if (!sourceRoot.startsWith(definitionDir) || !Files.isDirectory(sourceRoot)) {
            throw new IllegalStateException("conversion '" + id
                    + "' javaHook.source points to a missing directory: " + javaHook.source());
        }
        Path mainClassSource = sourceRoot.resolve(javaHook.className().replace('.', '/') + ".java").normalize();
        if (!mainClassSource.startsWith(sourceRoot) || !Files.isRegularFile(mainClassSource)) {
            throw new IllegalStateException("conversion '" + id + "' javaHook.class '" + javaHook.className()
                    + "' has no matching .java file under " + javaHook.source());
        }

        List<String> classResources = copySource(sourceRoot, outRoot);
        appendBytecodeGateEntries(outRoot, classResources);
        appendRuntimeRefEntry(outRoot, conversion);
        for (String manifestFileName : MANIFEST_FILE_NAMES) {
            appendPluginManifestEntry(outRoot, manifestFileName, conversion);
        }
    }

    private static List<String> copySource(Path sourceRoot, Path outRoot) throws IOException {
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            sources = stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> sourceRoot.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
        List<String> classResources = new ArrayList<>();
        for (Path source : sources) {
            String relative = sourceRoot.relativize(source).toString().replace('\\', '/');
            String destination = "src/main/java/" + relative;
            String sourceText = Files.readString(source, StandardCharsets.UTF_8);
            // SEC-3/B30 generation-side admission: refuse an unsafe hook at generation time instead
            // of baking it into an app that then fails the boot gate -- same call RuntimeApiEmitter
            // makes for a plugin:java-source mount's own source files.
            PluginJavaSourcePolicy.validatePluginJavaSource(sourceText, destination);
            Path destPath = outRoot.resolve(destination.replace('/', File.separatorChar));
            Files.createDirectories(destPath.getParent());
            Files.writeString(destPath, sourceText, StandardCharsets.UTF_8);
            classResources.add(relative.substring(0, relative.length() - ".java".length()) + ".class");
        }
        return classResources;
    }

    /** Mirrors {@code RuntimeApiEmitter#emitPluginBytecodeGateManifestIfNeeded}'s output shape --
     *  {@code PluginBytecodeBootGate} reads this file at boot to know which compiled classes to
     *  bytecode-scan. Merges with whatever RuntimeApiEmitter already wrote (plugin:java-source/
     *  controller mounts, if any) rather than overwriting it. */
    private static void appendBytecodeGateEntries(Path outRoot, List<String> classResources) throws IOException {
        Path manifestPath = outRoot.resolve("src/main/resources/npdev/plugin-bytecode/plugin-owned-classes.txt"
                .replace('/', File.separatorChar));
        TreeSet<String> merged = new TreeSet<>();
        if (Files.isRegularFile(manifestPath)) {
            for (String line : Files.readAllLines(manifestPath, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    merged.add(line.trim());
                }
            }
        }
        merged.addAll(classResources);
        Files.createDirectories(manifestPath.getParent());
        Files.writeString(manifestPath, String.join("\n", merged) + "\n", StandardCharsets.UTF_8);
    }

    /** Mirrors {@code RuntimeApiEmitter#emitJavaSourceRuntimeRefManifestIfNeeded}'s output shape --
     *  {@code ManifestDrivenJavaSourcePluginHandler} (child side, reused verbatim) resolves a mount's
     *  FQCN + operation->method map from this SAME file at invoke time, by {@code capability}. A
     *  javaHook conversion has exactly one operation (its declared method), keyed to itself so the
     *  manifest shape stays uniform with a real plugin:java-source mount's (possibly multi-operation)
     *  methodByOperation map. */
    private static void appendRuntimeRefEntry(Path outRoot, CompiledConversion conversion) throws IOException {
        Path manifestPath = outRoot.resolve("src/main/resources/npdev/plugin-runtime/java-source-runtime-refs.json"
                .replace('/', File.separatorChar));
        ObjectNode root = readOrCreateObject(manifestPath, "manifestVersion", "1.0.0");
        ArrayNode entries = root.withArray("javaSourcePlugins");
        ObjectNode entry = OBJECT_MAPPER.createObjectNode();
        String method = conversion.javaHook().method();
        entry.put("capability", conversionHookCapability(conversion));
        entry.put("capabilityType", "ConversionHookJavaHook");
        entry.put("adapterId", javaMigrationHookAdapterId());
        entry.put("pluginId", conversionHookPluginId(conversion));
        entry.put("runtimeRef", conversion.id());
        entry.put("mainClass", conversion.javaHook().className());
        ObjectNode methodByOperation = entry.putObject("methodByOperation");
        methodByOperation.put(method, method);
        entries.add(entry);
        writeJson(manifestPath, root);
    }

    /** Mirrors {@code RuntimeApiEmitter#emitPluginManifest}'s output shape -- ONE adapter row (the
     *  invoke contribution {@code PluginIpcChildProcessPool.invoke} resolves via {@code
     *  RuntimePluginAdapterRegistry.requireContribution}). No declared-callback row: the child's
     *  method return value carries the write back to the host directly (see this class's own
     *  javadoc), so there is nothing for the child to call back for. */
    private static void appendPluginManifestEntry(Path outRoot, String fileName, CompiledConversion conversion)
            throws IOException {
        Path manifestPath = outRoot.resolve(("src/main/resources/npdev/plugins/" + fileName)
                .replace('/', File.separatorChar));
        ObjectNode root = readOrCreateObject(manifestPath, "manifestVersion", "1.0");
        ArrayNode plugins = root.withArray("plugins");
        String capability = conversionHookCapability(conversion);
        String method = conversion.javaHook().method();
        String adapterId = javaMigrationHookAdapterId();

        ObjectNode invokeAdapter = OBJECT_MAPPER.createObjectNode();
        invokeAdapter.put("capability", capability);
        invokeAdapter.put("operation", method);
        invokeAdapter.put("adapterId", adapterId);
        invokeAdapter.put("bindingKey", bindingKey(capability, adapterId));
        ObjectNode invokeImplementation = OBJECT_MAPPER.createObjectNode();
        invokeImplementation.put("kind", "runtimeRef");
        invokeImplementation.put("ref", conversion.id());
        invokeAdapter.set("implementation", invokeImplementation);

        ArrayNode adapters = OBJECT_MAPPER.createArrayNode();
        adapters.add(invokeAdapter);

        ObjectNode plugin = OBJECT_MAPPER.createObjectNode();
        plugin.put("pluginId", conversionHookPluginId(conversion));
        plugin.put("displayName", "Conversion hook '" + conversion.id() + "' Java migration hook");
        plugin.put("version", "1.0.0");
        plugin.put("enabled", true);
        plugin.set("adapters", adapters);
        plugins.add(plugin);

        writeJson(manifestPath, root);
    }

    private static ObjectNode readOrCreateObject(Path path, String versionKey, String versionValue) throws IOException {
        if (Files.isRegularFile(path)) {
            JsonNode existing = OBJECT_MAPPER.readTree(stripBom(Files.readString(path, StandardCharsets.UTF_8)));
            return (ObjectNode) existing;
        }
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put(versionKey, versionValue);
        return root;
    }

    private static void writeJson(Path path, ObjectNode root) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String stripBom(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static String conversionHookCapability(CompiledConversion conversion) {
        return "conversionHook:" + conversion.id();
    }

    private static String conversionHookPluginId(CompiledConversion conversion) {
        return "conversion-hook-" + conversion.id().toLowerCase(Locale.ROOT);
    }

    private static String javaMigrationHookAdapterId() {
        return "plugin:java-migration-hook";
    }

    private static String bindingKey(String capability, String adapterId) {
        return normalizeKey(capability) + "|" + normalizeKey(adapterId);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
