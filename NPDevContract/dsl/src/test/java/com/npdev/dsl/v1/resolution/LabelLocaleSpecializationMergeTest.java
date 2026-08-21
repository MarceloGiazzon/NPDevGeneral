package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.6: {@link ModelResolver}'s specialize/extend merge now carries a label site's text AND its
 * locale map as one unit ({@code ModelResolver.mergeLabelText}/{@code mergeLabelLocales}, both
 * evaluated from the SAME {@code hasLabelContent(text, locales)} pair-check) rather than the
 * pre-R5.6 scalar {@code firstNonBlank(text, text)}. Proves the documented rule from
 * {@code BREAKING.md}'s R5.6 entry: "a specialization declaring ANY label content, text or locale
 * map, replaces the base's entirely" -- no per-locale-key merge, so a specialization that narrows
 * (or completely changes) its declared locale set is not silently backfilled with the base's other
 * locales.
 */
class LabelLocaleSpecializationMergeTest {

    @Test
    void specializationsLocaleMapWhollyReplacesTheBasesInsteadOfMerging() throws Exception {
        ConceptAst widget = resolveWidget("""
                {
                  "default": "Override label",
                  "pt-BR": "Rotulo novo"
                }
                """);

        assertEquals("Override label", widget.getUi().getLabel());
        // The base declares "fr" too (see baseModel()) -- it must NOT survive into the resolved
        // concept. A per-key merge would have kept it; a whole-value override does not.
        assertEquals(Map.of("pt-BR", "Rotulo novo"), widget.getUi().getLabelLocales());
    }

    @Test
    void specializationsPlainStringLabelDropsTheBasesLocaleMapEntirely() throws Exception {
        // The specialization declares ONLY a plain string (no locale map at all). hasLabelContent
        // is true because the text is non-blank, so mergeLabelText/mergeLabelLocales both take the
        // OVERRIDE side of the pair -- an empty locales map -- rather than leaving the base's rich
        // locale map in place because "the override didn't mention locales".
        ConceptAst widget = resolveWidget("\"Override label\"");

        assertEquals("Override label", widget.getUi().getLabel());
        assertTrue(widget.getUi().getLabelLocales().isEmpty(),
                "a specialization's plain-string label must drop the base's locale map, not inherit it: "
                        + widget.getUi().getLabelLocales());
    }

    @Test
    void specializationDeclaringNoUiBlockAtAllInheritsTheBasesLabelAndLocalesWhole() throws Exception {
        ModelAst source = parseJson(baseAndSpecializationModel(null));
        ConceptAst widget = new ModelResolver().resolve(source).modelAst().getConcepts().stream()
                .filter(c -> "SpecialWidget".equals(c.getName())).findFirst().orElseThrow();

        assertEquals("Base label", widget.getUi().getLabel());
        assertEquals(Map.of("pt-BR", "Rotulo base", "fr", "Etiquette base"), widget.getUi().getLabelLocales());
    }

    private static ConceptAst resolveWidget(String overrideLabelJson) throws Exception {
        ModelAst source = parseJson(baseAndSpecializationModel(overrideLabelJson));
        return new ModelResolver().resolve(source).modelAst().getConcepts().stream()
                .filter(c -> "SpecialWidget".equals(c.getName())).findFirst().orElseThrow();
    }

    /**
     * {@code overrideLabelJson} is spliced verbatim as the specialization's {@code ui.label} value
     * (a JSON literal -- string or object); {@code null} omits the specialization's {@code ui}
     * block entirely (pure inheritance case).
     */
    private static String baseAndSpecializationModel(String overrideLabelJson) {
        String specializationUiBlock = overrideLabelJson == null
                ? ""
                : ("\"ui\": { \"label\": " + overrideLabelJson + " },");
        return """
                {
                  "namespace": "wms.labelmerge",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "BaseWidget",
                      "ui": { "label": { "default": "Base label", "pt-BR": "Rotulo base", "fr": "Etiquette base" } },
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "SpecialWidget",
                      "specializes": "BaseWidget",
                      %s
                      "fields": [
                        { "name": "extra", "type": "string" }
                      ]
                    }
                  ]
                }
                """.formatted(specializationUiBlock);
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path temp = Files.createTempFile("npdev-label-locale-merge-", ".json");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(temp);
    }
}
