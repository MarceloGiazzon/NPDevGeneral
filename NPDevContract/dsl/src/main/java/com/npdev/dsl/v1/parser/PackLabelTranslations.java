package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REG-353 point 2: the labels of a pack in the languages the platform ships, without touching the pack.
 *
 * <p>A pack is written in English only (it is published for everyone, see REG-347) and a published version is immutable, so
 * its translations cannot live inside it. They live here instead, as the platform's own catalogue
 * ({@code pack-i18n/<packId>.<locale>.json} on the classpath), and are applied when a pack is LOADED into an app:
 * a {@code "label": "Sales order"} becomes {@code "label": {"default": "Sales order", "pt-BR": "Pedido de venda"}}, which the
 * rest of the pipeline already understands (label maps). Nothing on disk changes, so pack digests and lock files stay valid.
 *
 * <p>Safe by construction: a label is translated only when its path is in the catalogue AND its English text equals the
 * catalogue's recorded English, so a newer version of the pack that reworded a label is left alone (English) instead of being
 * given a wrong translation; a label that already carries a map keeps what its author wrote and only gains a missing locale.
 * A path is the chain of object keys and, inside an array, the element's {@code name} or {@code value} (else its index),
 * down to the object that holds the {@code label}, for example {@code concepts/SalesOrder/fields/orderDate/ui}.
 */
final class PackLabelTranslations {

    /** The languages the platform's own texts ship in besides English (kept in step with BusinessUiEmitter.SHIPPED_LOCALES). */
    static final List<String> LOCALES = List.of("pt-BR");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode NONE = MAPPER.createObjectNode();
    private static final Map<String, JsonNode> CACHE = new ConcurrentHashMap<>();

    private PackLabelTranslations() {
    }

    /** Adds the catalogue's translations to {@code pack} in place. A pack with no catalogue is left exactly as it is. */
    static void apply(ObjectNode pack) {
        JsonNode idNode = pack.get("pack");
        if (idNode == null || !idNode.isTextual() || !idNode.asText().matches("[a-z][a-z0-9_-]*")) {
            return;
        }
        for (String locale : LOCALES) {
            JsonNode labels = catalogue(idNode.asText(), locale);
            if (labels != NONE) {
                walk(pack, "", labels, locale);
            }
        }
    }

    /** The catalogue's {@code labels} object for one pack and locale, or NONE. */
    private static JsonNode catalogue(String packId, String locale) {
        return CACHE.computeIfAbsent(packId + "." + locale, key -> {
            try (InputStream in = PackLabelTranslations.class.getResourceAsStream("/pack-i18n/" + key + ".json")) {
                if (in == null) {
                    return NONE;
                }
                JsonNode labels = MAPPER.readTree(in).get("labels");
                return labels != null && labels.isObject() ? labels : NONE;
            } catch (IOException exception) {
                return NONE;
            }
        });
    }

    /** Every path the catalogue could translate, for the test that proves nothing in a built-in pack is left out. */
    static List<String> labelPaths(JsonNode pack) {
        List<String> out = new ArrayList<>();
        collect(pack, "", out);
        return out;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node instanceof ObjectNode object) {
            if (object.has("label")) {
                out.add(path);
            }
            object.fields().forEachRemaining(entry -> collect(entry.getValue(), join(path, entry.getKey()), out));
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                collect(array.get(i), join(path, segment(array.get(i), i)), out);
            }
        }
    }

    private static void walk(JsonNode node, String path, JsonNode labels, String locale) {
        if (node instanceof ObjectNode object) {
            JsonNode label = object.get("label");
            JsonNode entry = label == null ? null : labels.get(path);
            if (entry != null) {
                translate(object, label, entry, locale);
            }
            List<Map.Entry<String, JsonNode>> children = new ArrayList<>();
            object.fields().forEachRemaining(children::add);
            for (Map.Entry<String, JsonNode> child : children) {
                walk(child.getValue(), join(path, child.getKey()), labels, locale);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                walk(array.get(i), join(path, segment(array.get(i), i)), labels, locale);
            }
        }
    }

    private static void translate(ObjectNode owner, JsonNode label, JsonNode entry, String locale) {
        String english = text(entry.get("en"));
        String translated = text(entry.get("text"));
        if (english.isEmpty() || translated.isEmpty()) {
            return;
        }
        if (label.isTextual() && english.equals(label.asText())) {
            ObjectNode map = JsonNodeFactory.instance.objectNode();
            map.put("default", english);
            map.put(locale, translated);
            owner.set("label", map);
        } else if (label instanceof ObjectNode map && english.equals(text(map.get("default"))) && !map.has(locale)) {
            map.put(locale, translated);
        }
    }

    private static String segment(JsonNode element, int index) {
        if (element instanceof ObjectNode object) {
            String name = text(object.get("name"));
            if (!name.isEmpty()) {
                return name;
            }
            String value = text(object.get("value"));
            if (!value.isEmpty()) {
                return value;
            }
        }
        return String.valueOf(index);
    }

    private static String join(String path, String segment) {
        return path.isEmpty() ? segment : path + "/" + segment;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : "";
    }
}
