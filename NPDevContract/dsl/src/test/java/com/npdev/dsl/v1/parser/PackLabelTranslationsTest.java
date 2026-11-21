package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** REG-353 point 2: the platform's Portuguese catalogue for the labels of the built-in packs. */
class PackLabelTranslationsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode parse(String json) throws IOException {
        return (ObjectNode) MAPPER.readTree(json);
    }

    @Test
    void everyLabelOfEveryBuiltInPackHasAPortugueseTranslation() throws Exception {
        Path packs = Path.of("..", "packs").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(packs), "built-in packs folder must exist at " + packs);
        List<String> untranslated = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> dirs = Files.list(packs)) {
            for (Path dir : (Iterable<Path>) dirs.sorted()::iterator) {
                Path file = dir.resolve("pack.json");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                ObjectNode pack = (ObjectNode) MAPPER.readTree(Files.readString(file));
                PackLabelTranslations.apply(pack);
                for (String path : PackLabelTranslations.labelPaths(pack)) {
                    if (path.startsWith("flows/")) {
                        continue; // humanTask fields/outcomes accept only plain text in model.schema.json
                    }
                    JsonNode label = at(pack, path).get("label");
                    checked++;
                    if (!label.isObject() || !label.has("pt-BR")) {
                        untranslated.add(dir.getFileName() + ": " + path + " = " + label);
                    }
                }
            }
        }
        assertTrue(checked > 200, "the built-in packs carry about 230 labels, saw " + checked);
        assertEquals(List.of(), untranslated,
                "a built-in pack label with no translation in pack-i18n/<pack>.pt-BR.json (or whose English changed)");
    }

    @Test
    void aLabelIsGivenItsTranslationAndKeepsTheEnglishAsTheDefault() throws Exception {
        ObjectNode pack = parse("{\"pack\":\"sales\",\"concepts\":[{\"name\":\"Customer\",\"ui\":{\"label\":\"Customer\"}}]}");
        PackLabelTranslations.apply(pack);
        JsonNode label = pack.get("concepts").get(0).get("ui").get("label");
        assertEquals("Customer", label.get("default").asText());
        assertEquals("Cliente", label.get("pt-BR").asText());
    }

    @Test
    void aLabelWhoseEnglishChangedAndAnUnknownPackAreLeftAlone() throws Exception {
        ObjectNode reworded = parse("{\"pack\":\"sales\",\"concepts\":[{\"name\":\"Customer\",\"ui\":{\"label\":\"Client\"}}]}");
        PackLabelTranslations.apply(reworded);
        assertEquals("Client", reworded.get("concepts").get(0).get("ui").get("label").asText());

        ObjectNode unknown = parse("{\"pack\":\"nope\",\"concepts\":[{\"name\":\"Customer\",\"ui\":{\"label\":\"Customer\"}}]}");
        PackLabelTranslations.apply(unknown);
        assertEquals("Customer", unknown.get("concepts").get(0).get("ui").get("label").asText());
    }

    @Test
    void anAuthorsOwnLabelMapIsKeptAndOnlyAMissingLocaleIsAdded() throws Exception {
        ObjectNode pack = parse("{\"pack\":\"sales\",\"concepts\":["
                + "{\"name\":\"Customer\",\"ui\":{\"label\":{\"default\":\"Customer\",\"es\":\"Cliente\"}}},"
                + "{\"name\":\"Invoice\",\"ui\":{\"label\":{\"default\":\"Invoice\",\"pt-BR\":\"Nota\"}}}]}");
        PackLabelTranslations.apply(pack);
        JsonNode customer = pack.get("concepts").get(0).get("ui").get("label");
        assertEquals("Cliente", customer.get("es").asText());
        assertEquals("Cliente", customer.get("pt-BR").asText());
        assertEquals("Nota", pack.get("concepts").get(1).get("ui").get("label").get("pt-BR").asText(),
                "what the pack's author wrote is never overwritten");
    }

    @Test
    void aPackIdThatCouldNameAnotherResourceIsIgnored() throws Exception {
        ObjectNode pack = parse("{\"pack\":\"../sales\",\"concepts\":[{\"name\":\"Customer\",\"ui\":{\"label\":\"Customer\"}}]}");
        PackLabelTranslations.apply(pack);
        assertFalse(pack.get("concepts").get(0).get("ui").get("label").isObject());
    }

    /** Follows a catalogue path (object keys; array elements by name, value or index) the same way the class builds it. */
    private static JsonNode at(JsonNode root, String path) {
        JsonNode node = root;
        for (String segment : path.isEmpty() ? new String[0] : path.split("/")) {
            if (node.isArray()) {
                JsonNode found = null;
                for (int i = 0; i < node.size() && found == null; i++) {
                    JsonNode element = node.get(i);
                    if (segment.equals(element.path("name").asText("")) || segment.equals(element.path("value").asText(""))
                            || segment.equals(String.valueOf(i))) {
                        found = element;
                    }
                }
                node = found;
            } else {
                node = node.get(segment);
            }
            assertTrue(node != null, "path not found: " + path);
        }
        return node;
    }
}
