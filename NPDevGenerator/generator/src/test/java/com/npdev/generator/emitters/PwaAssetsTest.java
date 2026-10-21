package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MOB-1: the manifest and icons every generated UI carries. */
class PwaAssetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel model(String guidePagesJson) throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "demo.pwa", "version": "1.0",
              "concepts": [ { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]
              %s
            }
            """.formatted(guidePagesJson);
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    @Test
    void anUnbrandedModelFallsBackToItsNamespaceAndThePlatformBlue() throws Exception {
        CompiledModel model = model("");
        JsonNode manifest = MAPPER.readTree(PwaAssets.manifestJson(model));
        assertEquals("demo.pwa", manifest.get("name").asText());
        assertEquals("demo.pwa".substring(0, 8), manifest.get("short_name").asText());
        assertEquals("#2563eb", manifest.get("theme_color").asText());
        assertEquals("standalone", manifest.get("display").asText());
        assertEquals("/npdev-business-ui/index.html", manifest.get("start_url").asText());
        assertEquals(2, manifest.get("icons").size());
        assertEquals("any maskable", manifest.get("icons").get(1).get("purpose").asText());
        assertEquals("NPDev App", PwaAssets.appName(null));
        assertEquals("#2563eb", PwaAssets.accent(null));
    }

    @Test
    void theDefaultGuidePagesLogoTextAndAccentBrandTheApp() throws Exception {
        CompiledModel model = model(", \"guidePages\": [ { \"name\": \"Default\", \"default\": true, "
                + "\"theme\": { \"accent\": \"#112233\", \"logoText\": \"Acme Warehouse Cloud\" } } ]");
        JsonNode manifest = MAPPER.readTree(PwaAssets.manifestJson(model));
        assertEquals("Acme Warehouse Cloud", manifest.get("name").asText());
        assertEquals("Acme Wareho", manifest.get("short_name").asText().substring(0, 11));
        assertEquals("#112233", manifest.get("theme_color").asText());
    }

    @Test
    void aNonHexAccentIsIgnoredRatherThanWrittenIntoTheManifest() throws Exception {
        CompiledModel model = model(", \"guidePages\": [ { \"name\": \"Default\", \"default\": true, \"theme\": { \"accent\": \"red\" } } ]");
        assertEquals("#2563eb", PwaAssets.accent(model));
    }

    @Test
    void iconsAreRealPngsOfTheRequestedSizeAndByteStable() throws Exception {
        byte[] first = PwaAssets.iconPng(192, "#112233");
        byte[] second = PwaAssets.iconPng(192, "#112233");
        assertArrayEquals(first, second, "generation must be deterministic");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(first));
        assertEquals(192, image.getWidth());
        assertEquals(192, image.getHeight());
        assertEquals(0x112233, image.getRGB(2, 2) & 0xFFFFFF, "the corner is the accent colour");
        assertTrue(PwaAssets.iconPng(512, "#112233").length > first.length);
    }
}
