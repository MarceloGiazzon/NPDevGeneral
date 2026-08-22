package com.npdev.dsl.v1.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code npdev pack search}: searches a pack catalog for packs matching a query and/or category.
 * <p>
 * Loads a pack-catalog.json (local file or URL), filters by substring match on packId/description/
 * concepts and/or exact category match, and prints results as a formatted table.
 * <p>
 * Exit 0 = results found, exit 1 = no results, exit 2 = usage/IO error.
 */
public final class PackSearchMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PackSearchMain() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        Locale.setDefault(Locale.ENGLISH);

        String query = null;
        String category = null;
        String catalog = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--query".equals(arg) && i + 1 < args.length) {
                query = args[++i];
            } else if (arg.startsWith("--query=")) {
                query = arg.substring("--query=".length());
            } else if ("--category".equals(arg) && i + 1 < args.length) {
                category = args[++i];
            } else if (arg.startsWith("--category=")) {
                category = arg.substring("--category=".length());
            } else if ("--catalog".equals(arg) && i + 1 < args.length) {
                catalog = args[++i];
            } else if (arg.startsWith("--catalog=")) {
                catalog = arg.substring("--catalog=".length());
            }
        }

        if (catalog == null) {
            System.err.println("usage: PackSearchMain --catalog <url-or-path> [--query <text>] [--category <cat>]");
            return 2;
        }

        JsonNode root;
        try {
            root = loadCatalog(catalog);
        } catch (IOException e) {
            System.err.println("error: failed to load catalog: " + e.getMessage());
            return 2;
        }

        JsonNode packsNode = root.get("packs");
        if (packsNode == null || !packsNode.isArray()) {
            System.err.println("error: catalog has no 'packs' array");
            return 2;
        }

        String queryLower = query != null ? query.toLowerCase(Locale.ENGLISH) : null;
        String categoryLower = category != null ? category.toLowerCase(Locale.ENGLISH) : null;

        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode pack : packsNode) {
            if (categoryLower != null) {
                String cat = pack.has("category") ? pack.get("category").asText() : "";
                if (!cat.toLowerCase(Locale.ENGLISH).equals(categoryLower)) {
                    continue;
                }
            }
            if (queryLower != null) {
                if (!matchesQuery(pack, queryLower)) {
                    continue;
                }
            }
            matches.add(pack);
        }

        if (matches.isEmpty()) {
            String label = query != null ? query : (category != null ? category : "");
            System.out.println("No packs found matching '" + label + "'");
            return 1;
        }

        // Print formatted table
        int idWidth = "packId".length();
        int verWidth = "version".length();
        int descWidth = "description".length();
        int catWidth = "category".length();

        for (JsonNode pack : matches) {
            idWidth = Math.max(idWidth, textOr(pack, "packId", "").length());
            verWidth = Math.max(verWidth, textOr(pack, "version", "").length());
            descWidth = Math.max(descWidth, textOr(pack, "description", "").length());
            catWidth = Math.max(catWidth, textOr(pack, "category", "").length());
        }

        String fmt = "%-" + idWidth + "s | %-" + verWidth + "s | %-" + descWidth + "s | %-" + catWidth + "s%n";
        System.out.printf(fmt, "packId", "version", "description", "category");
        System.out.printf(fmt,
                "-".repeat(idWidth),
                "-".repeat(verWidth),
                "-".repeat(descWidth),
                "-".repeat(catWidth));

        for (JsonNode pack : matches) {
            System.out.printf(fmt,
                    textOr(pack, "packId", ""),
                    textOr(pack, "version", ""),
                    textOr(pack, "description", ""),
                    textOr(pack, "category", ""));
        }

        return 0;
    }

    private static boolean matchesQuery(JsonNode pack, String queryLower) {
        // Match against packId
        String packId = textOr(pack, "packId", "").toLowerCase(Locale.ENGLISH);
        if (packId.contains(queryLower)) return true;

        // Match against description
        String desc = textOr(pack, "description", "").toLowerCase(Locale.ENGLISH);
        if (desc.contains(queryLower)) return true;

        // Match against concepts
        JsonNode concepts = pack.get("concepts");
        if (concepts != null && concepts.isArray()) {
            for (JsonNode concept : concepts) {
                if (concept.asText().toLowerCase(Locale.ENGLISH).contains(queryLower)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static JsonNode loadCatalog(String catalog) throws IOException {
        if (catalog.startsWith("http://") || catalog.startsWith("https://")) {
            try (InputStream in = URI.create(catalog).toURL().openStream()) {
                return MAPPER.readTree(in);
            }
        }
        return MAPPER.readTree(Files.readString(Path.of(catalog)));
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        JsonNode child = node.get(field);
        return (child != null && !child.isNull()) ? child.asText() : fallback;
    }
}
