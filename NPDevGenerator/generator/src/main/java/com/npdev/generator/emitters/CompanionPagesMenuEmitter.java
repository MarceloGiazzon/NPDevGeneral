package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Menu entries for an app's hand-made companion pages ("path 4": {@code web/*.html}, wrapped by the
 * shell). Reads the two optional files that sit NEXT TO {@code model.json}:
 * <ul>
 *   <li>{@code pages.json} -- {@code [{ path, label, requiredRole?, ordinal? }]}, one root PAGE entry each;</li>
 *   <li>{@code menu.json} -- a tree {@code { label, ordinal?, requiredRole?, kind?, target?, children? }}
 *       flattened depth-first (kind defaults to GROUP with children, else BUSINESS).</li>
 * </ul>
 * and writes {@code npdev-seed/workspace-menu-pages-seed.json}, which {@code WorkspaceMenuSeeder}
 * reconciles into {@code workspace::Menu} at boot.
 *
 * <p>Moved here 2026-10-07 (Pigmentampas, G1a) from {@code scripts/appgen/Build-NpdevApp.ps1}, where
 * only AppGen-built apps got it -- a sample or {@code npdev generate} app with {@code web/} pages had
 * no menu entries. Same semantics as that script: content-derived {@code key}/{@code parentKey}
 * (kind+target, or kind+label for a target-less group, chained under the parent's key; true
 * duplicates get {@code #2}, {@code #3}...), a {@code pages.json} page already placed in the
 * {@code menu.json} tree as a PAGE node is not seeded twice, and pages without an ordinal get
 * 1000, 1010, ...
 */
public final class CompanionPagesMenuEmitter {

    static final String SEED_PATH = "src/main/resources/npdev-seed/workspace-menu-pages-seed.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final GeneratedSourceWriter writer;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Set<String> seenKeys = new HashSet<>();
    private final Set<String> menuTreePageTargets = new HashSet<>();

    public CompanionPagesMenuEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(Path modelSourcePath) {
        if (modelSourcePath == null) {
            return;
        }
        Path dir = modelSourcePath.toAbsolutePath().normalize().getParent();
        Path pages = dir.resolve("pages.json");
        Path menu = dir.resolve("menu.json");
        boolean hasPages = Files.isRegularFile(pages);
        boolean hasMenu = Files.isRegularFile(menu);
        if (!hasPages && !hasMenu) {
            return;
        }
        if (hasMenu) {
            for (JsonNode root : read(menu)) {
                addMenuNode(root, null);
            }
        }
        if (hasPages) {
            int nextOrdinal = 1000;
            for (JsonNode page : read(pages)) {
                String path = text(page, "path");
                if (menuTreePageTargets.contains(path)) {
                    continue;
                }
                Object ordinal = page.hasNonNull("ordinal") ? page.get("ordinal").numberValue() : (Object) nextOrdinal;
                rows.add(row(key(null, "PAGE", path, text(page, "label")), null, text(page, "label"), path, "PAGE",
                        ordinal, page.hasNonNull("requiredRole") ? page.get("requiredRole").asText() : null));
                nextOrdinal += 10;
            }
        }
        try {
            writer.writeRelative(SEED_PATH, MAPPER.writeValueAsString(rows));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write " + SEED_PATH, e);
        }
    }

    private void addMenuNode(JsonNode node, String parentKey) {
        boolean hasChildren = node.has("children") && node.get("children").isArray() && !node.get("children").isEmpty();
        String kind = node.hasNonNull("kind") ? node.get("kind").asText() : (hasChildren ? "GROUP" : "BUSINESS");
        String target = node.hasNonNull("target") ? node.get("target").asText() : "";
        String label = text(node, "label");
        String ownKey = key(parentKey, kind, target, label);
        if ("PAGE".equals(kind) && !target.isEmpty()) {
            menuTreePageTargets.add(target);
        }
        rows.add(row(ownKey, parentKey, label, target, kind,
                node.hasNonNull("ordinal") ? node.get("ordinal").numberValue() : (Object) 0,
                node.hasNonNull("requiredRole") ? node.get("requiredRole").asText() : null));
        if (hasChildren) {
            for (JsonNode child : node.get("children")) {
                addMenuNode(child, ownKey);
            }
        }
    }

    private String key(String parentKey, String kind, String target, String label) {
        String leaf = target != null && !target.isEmpty() ? kind + ":" + target : kind + ":" + label;
        String candidate = parentKey != null ? parentKey + "/" + leaf : leaf;
        String unique = candidate;
        int suffix = 1;
        while (!seenKeys.add(unique)) {
            suffix += 1;
            unique = candidate + "#" + suffix;
        }
        return unique;
    }

    private static Map<String, Object> row(String key, String parentKey, String label, String target, String kind,
                                           Object ordinal, String requiredRole) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("parentKey", parentKey);
        row.put("label", label);
        row.put("target", target);
        row.put("kind", kind);
        row.put("ordinal", ordinal);
        row.put("requiredRole", requiredRole);
        row.put("visible", true);
        return row;
    }

    private static List<JsonNode> read(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            List<JsonNode> out = new ArrayList<>();
            if (root != null && root.isArray()) {
                root.forEach(out::add);
            } else if (root != null && root.isObject()) {
                out.add(root);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : "";
    }
}
