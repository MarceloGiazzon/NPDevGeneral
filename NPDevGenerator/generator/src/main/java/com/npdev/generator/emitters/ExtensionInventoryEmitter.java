package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledConversion;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.generator.emitters.trustedsource.model.TrustedReference;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Path A P0.3: the escape surface (119 files mention {@code TrustedSource}/{@code javaHook}/
 * {@code trustLevel}, 21 of them generator main classes -- {@code NPDEV_PATH_A_REALIGNMENT_PLAN.md}
 * section 3) is otherwise discoverable only by grep. This emitter makes it an artifact every
 * generation writes, over the same four real mechanisms the plan names -- no new escape-hatch
 * concept is introduced:
 *
 * <ul>
 *   <li><b>trustedSourceAsset</b> -- {@link TrustedSourceManifest#referencesFrom} (procedure/panel/
 *       widget files a trusted-source-manifest.json hash-locks). Origin is the asset's file path;
 *       owner is the model element ({@code id()}) that references it.</li>
 *   <li><b>javaHook</b> -- {@code conversions[].javaHook} ({@link CompiledConversion#javaHook()}),
 *       admitted by {@code ConversionHookJavaHookEmitter} through the same plugin machinery as a
 *       {@code plugin:java-source} mount, but declared inline rather than via {@code plugins[]}.
 *       Origin is the hook's class + method; owner is the conversion's {@code id()}.</li>
 *   <li><b>inProcessController</b> -- {@link GeneratedPluginMountPlan.Mount} entries of
 *       {@link GeneratedPluginMountPlan.MountKind#JAVA_CONTROLLER}: a plugin-mounted Java class
 *       running in this app's own process rather than dispatched over the plugin IPC boundary.
 *       Origin is the mounted controller's class name; owner is the capability requirement that
 *       bound it.</li>
 *   <li><b>pluginPackage</b> -- {@link GeneratedPluginMountPlan#packageGroups()}: every deployable
 *       plugin unit a {@code plugins[]}/capability binding mounted, regardless of mount kind. Origin
 *       is the package id; owner is the capability requirement that bound it.</li>
 * </ul>
 *
 * <p>None of these mechanisms carries a human-authorship field, so "owner" is defined as the model
 * element responsible for the extension existing -- the same reading {@code check-*-inventory}
 * scripts elsewhere in the repo use for "owner" when no author metadata exists.
 */
public final class ExtensionInventoryEmitter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OUTPUT_PATH = "src/main/resources/npdev/extension-inventory.json";

    private final GeneratedSourceWriter writer;

    public ExtensionInventoryEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(CompiledModel model, ResolvedModelSource resolvedModelSource, Path modelSourcePath) throws IOException {
        ArrayNode entries = OBJECT_MAPPER.createArrayNode();
        Map<String, Integer> counts = new TreeMap<>();

        for (TrustedReference reference : TrustedSourceManifest.referencesFrom(model)) {
            addEntry(entries, counts, "trustedSourceAsset", reference.kind(), reference.relativePath(), reference.id());
        }

        for (CompiledConversion conversion : model.getConversions()) {
            CompiledConversion.CompiledJavaHook javaHook = conversion.javaHook();
            if (javaHook == null) {
                continue;
            }
            String origin = javaHook.source() + "/" + javaHook.className().replace('.', '/') + ".java#" + javaHook.method();
            addEntry(entries, counts, "javaHook", "conversionJavaHook", origin, conversion.id());
        }

        GeneratedPluginMountPlan mountPlan = GeneratedPluginMountPlan.fromModelSource(resolvedModelSource, modelSourcePath);
        for (GeneratedPluginMountPlan.Mount mount : mountPlan.javaControllerMounts()) {
            addEntry(entries, counts, "inProcessController", "javaController", mount.controllerClassName(), mount.capability());
        }
        for (GeneratedPluginMountPlan.PackageGroup packageGroup : mountPlan.packageGroups()) {
            GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
            addEntry(entries, counts, "pluginPackage", representative.pluginId(), representative.packageId(), representative.capability());
        }

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        ObjectNode countsNode = root.putObject("counts");
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            countsNode.put(count.getKey(), count.getValue());
        }
        root.set("entries", entries);

        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
        writer.writeRelative(OUTPUT_PATH, json);
    }

    private static void addEntry(ArrayNode entries, Map<String, Integer> counts, String category, String kind,
            String origin, String owner) {
        ObjectNode entry = entries.addObject();
        entry.put("category", category);
        entry.put("kind", kind);
        entry.put("origin", origin);
        entry.put("owner", owner == null ? "" : owner);
        counts.merge(category, 1, Integer::sum);
    }
}
