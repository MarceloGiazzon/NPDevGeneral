package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledConversion;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.generator.emitters.customization.model.CustomizationRecord;
import com.npdev.generator.emitters.trustedsource.model.TrustedReference;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Path A P0.3: the escape surface (119 files mention {@code TrustedSource}/{@code javaHook}/
 * {@code trustLevel}, 21 of them generator main classes -- {@code NPDEV_PATH_A_REALIGNMENT_PLAN.md}
 * section 3) is otherwise discoverable only by grep. This emitter makes it an artifact every
 * generation writes, over the same four real mechanisms the plan names -- no new escape-hatch
 * concept is introduced:
 *
 * <ul>
 *   <li><b>untrustedExtensionAsset</b> -- {@link TrustedSourceManifest#referencesFrom} (procedure/
 *       panel/widget files an untrusted-extension-manifest.json hash-locks; Path A P0.4 renamed the
 *       author-facing vocabulary from "trusted source" to "untrusted extension" -- the internal
 *       Java class/package names in this package predate that rename and were deliberately left
 *       alone as non-author-facing). Origin is the asset's file path; owner is the model element
 *       ({@code id()}) that references it.</li>
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
 * <p>None of these mechanisms carries a human-authorship field on its own, so "owner" is defined as
 * the model element responsible for the extension existing -- the same reading {@code check-*-inventory}
 * scripts elsewhere in the repo use for "owner" when no author metadata exists.
 *
 * <p>Path A P5.2: each entry also carries a {@code provenance} object, joined by {@code owner} against
 * the OPTIONAL sibling {@code customization-provenance.json} ({@link CustomizationProvenanceManifest}).
 * An owner with no matching record gets {@code {"declared": false}} -- declaring nothing never blocks
 * generation, only a false claim would (the same rule P5.1 established for the untrusted-extension
 * zone's {@code truthStatus}).
 *
 * <p>Path A P5.3 (regeneration conflict outcomes): {@code untrustedExtensionAsset} and {@code javaHook}
 * entries also carry {@code generatedPaths} (the output-relative file(s) this owner's content actually
 * lands at) and a {@code contentHash} (SHA-256 over those files' bytes as written THIS run). {@link
 * com.npdev.generator.assembly.FinalAppAssembler} compares that hash, next regeneration, against a
 * fresh hash of what is still sitting on disk in the FinalApp before it wipes anything -- a mismatch
 * with no declared provenance record is undeclared drift (a hand-edit with no {@code
 * customization-provenance.json} entry), which is exactly the "anything else a user touched is
 * destroyed silently" gap P5.3 closes. {@code inProcessController}/{@code pluginPackage} entries get an
 * empty {@code generatedPaths} and no hash -- an honest, named limitation: those are plugin-mounted
 * compiled units with their own build/versioning lifecycle upstream of the generated App tree, not
 * hand-editable files inside it, so file-level drift detection does not apply to them in this pass.
 */
public final class ExtensionInventoryEmitter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String OUTPUT_PATH = "src/main/resources/npdev/extension-inventory.json";

    private final GeneratedSourceWriter writer;

    public ExtensionInventoryEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(CompiledModel model, ResolvedModelSource resolvedModelSource, Path modelSourcePath,
            Path outRoot, Map<String, List<String>> trustedSourceGeneratedPaths) throws IOException {
        ArrayNode entries = OBJECT_MAPPER.createArrayNode();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, CustomizationRecord> provenance = CustomizationProvenanceManifest.readSibling(modelSourcePath);

        for (TrustedReference reference : TrustedSourceManifest.referencesFrom(model)) {
            List<String> generatedPaths = trustedSourceGeneratedPaths.getOrDefault(
                    reference.kind() + "::" + reference.id(), List.of());
            addEntry(entries, counts, provenance, "untrustedExtensionAsset", reference.kind(),
                    reference.relativePath(), reference.id(), generatedPaths, outRoot);
        }

        for (CompiledConversion conversion : model.getConversions()) {
            CompiledConversion.CompiledJavaHook javaHook = conversion.javaHook();
            if (javaHook == null) {
                continue;
            }
            String origin = javaHook.source() + "/" + javaHook.className().replace('.', '/') + ".java#" + javaHook.method();
            List<String> generatedPaths = javaHookGeneratedPaths(javaHook, modelSourcePath);
            addEntry(entries, counts, provenance, "javaHook", "conversionJavaHook", origin, conversion.id(),
                    generatedPaths, outRoot);
        }

        GeneratedPluginMountPlan mountPlan = GeneratedPluginMountPlan.fromModelSource(resolvedModelSource, modelSourcePath);
        for (GeneratedPluginMountPlan.Mount mount : mountPlan.javaControllerMounts()) {
            addEntry(entries, counts, provenance, "inProcessController", "javaController", mount.controllerClassName(),
                    mount.capability(), List.of(), outRoot);
        }
        for (GeneratedPluginMountPlan.PackageGroup packageGroup : mountPlan.packageGroups()) {
            GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
            addEntry(entries, counts, provenance, "pluginPackage", representative.pluginId(), representative.packageId(),
                    representative.capability(), List.of(), outRoot);
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

    /**
     * Mirrors {@code ConversionHookJavaHookEmitter#copySource}'s destination formula ({@code
     * "src/main/java/" + <path under javaHook.source() relative to the model's definition dir>})
     * without threading a return value back through {@code ConversionHookEmitter}'s private
     * per-conversion emission -- the walk itself is simple and self-contained, and this emitter
     * already independently re-derives {@code origin} from the same {@code javaHook} fields rather
     * than consuming what that emitter wrote. Returns {@code List.of()} (never throws) when the
     * source directory is not present on disk, matching this emitter's established graceful-
     * degradation style for a model with no real source tree to inspect.
     */
    private static List<String> javaHookGeneratedPaths(CompiledConversion.CompiledJavaHook javaHook, Path modelSourcePath) {
        if (modelSourcePath == null || modelSourcePath.getParent() == null) {
            return List.of();
        }
        Path definitionDir = modelSourcePath.toAbsolutePath().normalize().getParent();
        Path sourceRoot = definitionDir.resolve(javaHook.source().replace('/', java.io.File.separatorChar)).normalize();
        if (!sourceRoot.startsWith(definitionDir) || !Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                    .map(path -> "src/main/java/" + sourceRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static void addEntry(ArrayNode entries, Map<String, Integer> counts,
            Map<String, CustomizationRecord> provenance, String category, String kind,
            String origin, String owner, List<String> generatedPaths, Path outRoot) {
        ObjectNode entry = entries.addObject();
        entry.put("category", category);
        entry.put("kind", kind);
        entry.put("origin", origin);
        entry.put("owner", owner == null ? "" : owner);
        ArrayNode generatedPathsNode = entry.putArray("generatedPaths");
        for (String path : generatedPaths) {
            generatedPathsNode.add(path);
        }
        String contentHash = GeneratedContentHash.of(outRoot, generatedPaths);
        if (contentHash != null) {
            entry.put("contentHash", contentHash);
        }
        ObjectNode provenanceNode = entry.putObject("provenance");
        CustomizationRecord record = owner == null ? null : provenance.get(owner);
        if (record == null) {
            provenanceNode.put("declared", false);
        } else {
            provenanceNode.put("declared", true);
            provenanceNode.put("changeSummary", record.changeSummary());
            provenanceNode.put("reason", record.reason());
            provenanceNode.put("author", record.author());
            provenanceNode.put("authorType", record.authorType());
            provenanceNode.put("regenerationIntent", record.regenerationIntent());
            provenanceNode.put("requiresRetest", record.requiresRetest());
            provenanceNode.put("releaseImpact", record.releaseImpact());
        }
        counts.merge(category, 1, Integer::sum);
    }
}
