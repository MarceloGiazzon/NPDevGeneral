package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledGuidePageGadget;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UI-2: the {@code page-fragment} gadget declares a same-origin {@code url}; a blank
 * {@code guidePageTheme.logoText}/{@code logoUrl} parses (blank = unset).
 */
class GuidePagePageFragmentValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String themeJson, String gadgetJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.fragment", "version": "1.0",
              "concepts": [
                { "name": "Widget", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "guidePages": [
                { "name": "Guide", "default": true, %s
                  "regions": { "right": { "enabled": true } },
                  "gadgets": [ %s ] }
              ]
            }
            """.formatted(themeJson, gadgetJson);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> validate(String json) throws Exception {
        return new SemanticValidator().validate(parse(json));
    }

    @Test
    void pageFragmentWithUrlPasses() throws Exception {
        List<String> errors = validate(model("",
                "{ \"name\": \"help\", \"type\": \"page-fragment\", \"url\": \"/npdev-help/x.html\" }"));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void pageFragmentWithoutUrlIsRejected() throws Exception {
        List<String> errors = validate(model("", "{ \"name\": \"help\", \"type\": \"page-fragment\" }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("gadget help")
                && e.contains("page-fragment gadgets must declare url")), errors.toString());
    }

    @Test
    void urlOnANonPageFragmentGadgetIsRejected() throws Exception {
        List<String> errors = validate(model("",
                "{ \"name\": \"k\", \"type\": \"recent-items\", \"url\": \"/x.html\" }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("gadget k")
                && e.contains("url is only valid on page-fragment gadgets")), errors.toString());
    }

    @Test
    void blankThemeLogoTextAndLogoUrlParse() throws Exception {
        ModelAst ast = parse(model("\"theme\": { \"logoText\": \"\", \"logoUrl\": \"\" },",
                "{ \"name\": \"help\", \"type\": \"page-fragment\", \"url\": \"/npdev-help/x.html\" }"));
        assertNotNull(ast);
    }

    @Test
    void urlSurvivesCompileAndCanonicalRoundTrip() throws Exception {
        ModelAst ast = parse(model("",
                "{ \"name\": \"help\", \"type\": \"page-fragment\", \"url\": \"/npdev-help/x.html\" }"));
        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledGuidePageGadget gadget = restored.getGuidePages().stream()
                .filter(p -> "Guide".equals(p.name())).findFirst().orElseThrow().gadgets().get(0);
        assertEquals("/npdev-help/x.html", gadget.url());
    }
}
