package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * FORM P5-T2: {@link ExperienceEmitter} writes the RESOLVED experiences.json (declaration order kept, dataUrl /
 * fieldMeta / startUrl added) only for a model that declares experiences, and copies the client files named by the
 * client manifest into static/x/.
 */
class ExperienceEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OUT = "src/main/resources/npdev/experiences.json";

    private static final String MODEL = """
            {
              "namespace": "experience.emitter",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "roles": [ { "name": "Agent", "concepts": { "Ticket": ["read", "create", "update"] } },
                         { "name": "Boss", "concepts": { "Ticket": ["read"] } } ],
              "concepts": [
                { "name": "Ticket", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string", "ui": { "label": "Headline" } },
                  { "name": "status", "type": "enum", "enumValues": ["OPEN", "DONE"], "required": true } ] },
                { "name": "TicketCategory", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "name", "type": "string" } ] }
              ],
              "queries": [ { "name": "OpenTickets", "concept": "Ticket", "where": "status == 'OPEN'" } ],
              "flows": [
                { "name": "CloseTicket", "expose": "ui", "steps": [
                  { "name": "answer", "type": "set", "value": "'ok'", "output": "$answer" },
                  { "name": "done", "type": "return", "value": "$answer" } ] }
              ],
              "experiences": [
                { "name": "zeta", "for": ["Boss"], "home": "categories" },
                { "name": "alpha", "for": ["Agent"], "home": "tickets",
                  "navigation": [ { "screen": "tickets", "label": "Tickets" }, { "screen": "open", "label": "Open" } ] }
              ],
              "screens": [
                { "name": "tickets", "kind": "list", "concept": "Ticket",
                  "columns": ["title", { "value": "status", "label": "State" }, "title.length"],
                  "actions": [ { "label": "Close", "flow": "closeticket", "then": "open" } ] },
                { "name": "open", "kind": "list", "query": "OpenTickets", "columns": ["title"] },
                { "name": "categories", "kind": "list", "concept": "TicketCategory", "columns": ["name"] }
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private CompiledModel compile(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    private ExperienceEmitter emitter(Path out, String clientBase) {
        return new ExperienceEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()), clientBase);
    }

    @Test
    void writesTheResolvedExperiencesInDeclarationOrder() throws Exception {
        Path out = tempDir.resolve("out");
        emitter(out, "experience-emitter-test/").emit(compile(MODEL));

        JsonNode resolved = MAPPER.readTree(Files.readString(out.resolve(OUT)));
        // Declaration order is load-bearing (first match of the caller's roles wins): zeta stays first.
        assertEquals("zeta", resolved.path("experiences").path(0).path("name").asText());
        assertEquals("alpha", resolved.path("experiences").path(1).path("name").asText());
        assertEquals(3, resolved.path("screens").size());

        JsonNode tickets = screen(resolved, "tickets");
        assertEquals("/api/concepts/tickets", tickets.path("dataUrl").asText());
        assertEquals("list", tickets.path("kind").asText(), "the authored screen is kept as is");
        assertEquals("string", tickets.path("fieldMeta").path("title").path("type").asText());
        assertEquals("Headline", tickets.path("fieldMeta").path("title").path("label").asText(), "ui.label wins");
        assertFalse(tickets.path("fieldMeta").path("status").has("label"), "no ui.label -> none is sent, the browser humanizes the name");
        assertFalse(tickets.path("fieldMeta").has("title.length"), "an expression is not a field");
        assertEquals("id", tickets.path("idField").asText());
        assertFalse(tickets.path("fieldMeta").path("title").path("required").asBoolean());
        assertTrue(tickets.path("fieldMeta").path("status").path("required").asBoolean());
        assertEquals("OPEN", tickets.path("fieldMeta").path("status").path("values").path(0).asText());
        assertEquals(2, tickets.path("fieldMeta").path("status").path("values").size());
        assertFalse(tickets.path("fieldMeta").path("title").has("values"), "values only for an enum field");
        JsonNode action = tickets.path("actions").path(0);
        assertEquals("/api/x/flows/CloseTicket/execute", action.path("startUrl").asText(), "canonical flow name");
        assertEquals("closeticket", action.path("flow").asText(), "the authored flow reference is untouched");

        // A concept whose name ends in y is served under the shared plural rule (REG-270).
        assertEquals("/api/concepts/ticket_categories", screen(resolved, "categories").path("dataUrl").asText());
        // A query screen is fetched from the real named-query route and shows its concept's fields.
        JsonNode open = screen(resolved, "open");
        assertEquals("/api/queries/OpenTickets", open.path("dataUrl").asText());
        assertEquals("Headline", open.path("fieldMeta").path("title").path("label").asText());
    }

    @Test
    void copiesEveryManifestFileUnderStaticX() throws Exception {
        Path out = tempDir.resolve("out");
        emitter(out, "experience-emitter-test/").emit(compile(MODEL));

        assertEquals("export const shell = 1;", Files.readString(out.resolve("src/main/resources/static/x/shell.js")).trim());
        assertEquals(".tile { color: red; }", Files.readString(out.resolve("src/main/resources/static/x/sub/tile.css")).trim());
    }

    @Test
    void aMissingClientManifestCopiesNothingAndDoesNotFail() throws Exception {
        Path out = tempDir.resolve("out");
        emitter(out, "experience-emitter-test-absent/").emit(compile(MODEL));

        assertTrue(Files.exists(out.resolve(OUT)), "experiences.json is still written");
        // only the generated home-screen icons (P5-T7) live under static/x/ without a manifest; no client file is copied
        try (var files = Files.walk(out.resolve("src/main/resources/static/x"))) {
            assertTrue(files.filter(Files::isRegularFile).allMatch(f -> f.toString().replace('\\', '/').contains("/static/x/icons/")),
                    "no client files without a manifest");
        }
    }

    @Test
    void theShippedEmitterWiringNeverFailsWhateverTheClientManifestState() throws Exception {
        Path out = tempDir.resolve("out");
        new ExperienceEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(compile(MODEL));
        assertTrue(Files.exists(out.resolve(OUT)));
    }

    @Test
    void aModelWithoutExperiencesWritesNothingAndRemovesAStaleFile() throws Exception {
        Path out = tempDir.resolve("out");
        Path stale = out.resolve(OUT);
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");

        CompiledModel bare = compile("""
                {
                  "namespace": "experience.none",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "concepts": [ { "name": "Ticket", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
                }
                """);
        emitter(out, "experience-emitter-test/").emit(bare);

        assertFalse(Files.exists(stale), "a stale file from an earlier generation is removed");
        assertFalse(Files.exists(out.resolve("src/main/resources/static/x")), "and no client files are copied");
    }

    // ---- FORM P5-T7: installable experiences ------------------------------------------------------------------

    private static final String ICONS = "src/main/resources/static/x/icons/";

    @Test
    void mobileAndBothExperiencesGetDeterministicPngIconsAndAWebOneGetsNone() throws Exception {
        String model = MODEL.replace("{ \"name\": \"zeta\", \"for\": [\"Boss\"], \"home\": \"categories\" }",
                "{ \"name\": \"zeta\", \"for\": [\"Boss\"], \"home\": \"categories\", \"device\": \"web\" }")
                .replace("\"name\": \"alpha\", \"for\": [\"Agent\"],", "\"name\": \"alpha\", \"title\": \"Beta desk\", \"for\": [\"Agent\"], \"device\": \"mobile\",");
        Path first = tempDir.resolve("one");
        Path second = tempDir.resolve("two");
        emitter(first, "experience-emitter-test/").emit(compile(model));
        emitter(second, "experience-emitter-test/").emit(compile(model));

        for (String size : new String[] {"192", "512"}) {
            byte[] bytes = Files.readAllBytes(first.resolve(ICONS + "alpha-" + size + ".png"));
            assertEquals(0x89, bytes[0] & 0xFF, "a PNG signature");
            assertEquals('P', bytes[1]);
            assertEquals(java.util.Arrays.hashCode(bytes), java.util.Arrays.hashCode(Files.readAllBytes(second.resolve(ICONS + "alpha-" + size + ".png"))));
            assertTrue(java.util.Arrays.equals(bytes, Files.readAllBytes(second.resolve(ICONS + "alpha-" + size + ".png"))),
                    "generating twice gives identical bytes");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertEquals(Integer.parseInt(size), image.getWidth());
            assertEquals(Integer.parseInt(size), image.getHeight());
            assertEquals(0x0b5fff, image.getRGB(1, 1) & 0xFFFFFF, "full-bleed accent colour (the shell default)");
        }
        assertFalse(Files.exists(first.resolve(ICONS + "zeta-192.png")), "a web experience has no icons");
        assertFalse(Files.exists(first.resolve(ICONS + "zeta-512.png")));
        JsonNode resolved = MAPPER.readTree(Files.readString(first.resolve(OUT)));
        assertEquals("#0b5fff", resolved.path("experiences").path(1).path("themeColor").asText());
        assertEquals("#ffffff", resolved.path("experiences").path(1).path("backgroundColor").asText());
    }

    @Test
    void theDarkThemeIdGetsItsOwnDefaultColours() throws Exception {
        String model = MODEL.replace("{ \"name\": \"zeta\", \"for\": [\"Boss\"], \"home\": \"categories\" }",
                "{ \"name\": \"zeta\", \"for\": [\"Boss\"], \"home\": \"categories\", \"theme\": \"dark\" }");
        Path out = tempDir.resolve("dark");
        emitter(out, "experience-emitter-test/").emit(compile(model));
        JsonNode resolved = MAPPER.readTree(Files.readString(out.resolve(OUT)));
        assertEquals("#6fa8ff", resolved.path("experiences").path(0).path("themeColor").asText());
        assertEquals("#14161a", resolved.path("experiences").path(0).path("backgroundColor").asText());
        assertTrue(Files.exists(out.resolve(ICONS + "zeta-192.png")));
    }

    // ---- FORM P5-T6: map tiles ----------------------------------------------------------------------------------

    private static final String MAP_SCREENS = "\"screens\": [ { \"name\": \"tickets\", \"kind\": \"list\", \"concept\": \"Ticket\", \"columns\": [\"title\"],"
            + " \"map\": { \"lat\": \"title\", \"lng\": \"title\" } },";

    @Test
    void aModelThatShowsAMapGetsOpenStreetMapTilesWithAttributionByDefault() throws Exception {
        Path out = tempDir.resolve("osm");
        emitter(out, "experience-emitter-test/").emit(compile(MODEL.replace("\"screens\": [", MAP_SCREENS)));
        JsonNode experience = MAPPER.readTree(Files.readString(out.resolve(OUT))).path("experiences").path(0);
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", experience.path("mapTilesUrl").asText());
        assertEquals("© OpenStreetMap contributors", experience.path("mapAttribution").asText());
    }

    @Test
    void theModelPropertyMapTilesUrlWinsAndUnusableValuesFallBack() throws Exception {
        String withProperty = MODEL.replace("\"screens\": [", MAP_SCREENS)
                .replace("\"queries\":", "\"properties\": [ { \"name\": \"mapTilesUrl\", \"type\": \"string\","
                        + " \"default\": \"https://tiles.example.org/{z}/{x}/{y}.png\", \"settableAt\": [\"tenant\"] },"
                        + " { \"name\": \"mapAttribution\", \"type\": \"string\", \"default\": \"Example maps\", \"settableAt\": [\"tenant\"] } ],"
                        + " \"propertyScopes\": [ { \"name\": \"tenant\" } ], \"queries\":");
        Path out = tempDir.resolve("prop");
        emitter(out, "experience-emitter-test/").emit(compile(withProperty));
        JsonNode experience = MAPPER.readTree(Files.readString(out.resolve(OUT))).path("experiences").path(0);
        assertEquals("https://tiles.example.org/{z}/{x}/{y}.png", experience.path("mapTilesUrl").asText());
        assertEquals("Example maps", experience.path("mapAttribution").asText());

        // a script URL is not a tile template: back to OSM; and attribution is never empty (host named)
        String hostile = withProperty.replace("https://tiles.example.org/{z}/{x}/{y}.png", "javascript:alert(1)");
        Path bad = tempDir.resolve("bad");
        emitter(bad, "experience-emitter-test/").emit(compile(hostile));
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
                MAPPER.readTree(Files.readString(bad.resolve(OUT))).path("experiences").path(0).path("mapTilesUrl").asText());
        String noAttribution = withProperty.replace("\"mapAttribution\"", "\"somethingElse\"");
        Path host = tempDir.resolve("host");
        emitter(host, "experience-emitter-test/").emit(compile(noAttribution));
        assertEquals("Map tiles: tiles.example.org",
                MAPPER.readTree(Files.readString(host.resolve(OUT))).path("experiences").path(0).path("mapAttribution").asText());
    }

    @Test
    void aModelWithoutAMapCarriesNoTileUrlAndAScreenCanNameItsOwnTileProperty() throws Exception {
        Path none = tempDir.resolve("none");
        emitter(none, "experience-emitter-test/").emit(compile(MODEL));
        assertFalse(MAPPER.readTree(Files.readString(none.resolve(OUT))).path("experiences").path(0).has("mapTilesUrl"));

        String own = MODEL.replace("\"screens\": [", "\"screens\": [ { \"name\": \"pins\", \"kind\": \"list\", \"concept\": \"Ticket\", \"columns\": [\"title\"],"
                        + " \"map\": { \"lat\": \"title\", \"lng\": \"title\", \"tiles\": \"satelliteTiles\" } },")
                .replace("\"queries\":", "\"properties\": [ { \"name\": \"satelliteTiles\", \"type\": \"string\","
                        + " \"default\": \"/tiles/{z}/{x}/{y}.png\", \"settableAt\": [\"tenant\"] } ], \"propertyScopes\": [ { \"name\": \"tenant\" } ], \"queries\":");
        Path out = tempDir.resolve("own");
        emitter(out, "experience-emitter-test/").emit(compile(own));
        JsonNode pins = screen(MAPPER.readTree(Files.readString(out.resolve(OUT))), "pins");
        assertEquals("/tiles/{z}/{x}/{y}.png", pins.path("map").path("tilesUrl").asText());
        assertEquals("Map tiles: provider", pins.path("map").path("attribution").asText());
    }

    private static JsonNode screen(JsonNode resolved, String name) {
        for (JsonNode screen : resolved.path("screens")) {
            if (name.equals(screen.path("name").asText())) {
                return screen;
            }
        }
        throw new AssertionError("no screen " + name);
    }
}
