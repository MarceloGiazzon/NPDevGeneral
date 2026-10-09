package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pigmentampas friction #18: compiled concepts store fields sorted by name (the canonical layout
 * schema and codegen rely on), so forms and detail views showed fields alphabetically. The author's
 * declaration order now survives as {@link CompiledConcept#getFieldOrder()} through compile and the
 * canonical-JSON round trip every generated app reads, while {@code getFields()} stays sorted.
 */
class ConceptFieldDeclaredOrderTest {

    private static final String MODEL = """
            {
              "namespace":"demo",
              "dslVersion":"1.0.0",
              "version":"v1",
              "concepts":[
                {"name":"Challenge","fields":[
                  {"name":"id","type":"uuid","id":true},
                  {"name":"title","type":"string"},
                  {"name":"month","type":"string"},
                  {"name":"status","type":"string"},
                  {"name":"theme","type":"string"}
                ]},
                {"name":"SponsoredChallenge","extends":"Challenge","fields":[
                  {"name":"sponsor","type":"string"},
                  {"name":"budget","type":"int"}
                ]}
              ]
            }
            """;

    private static final List<String> DECLARED = List.of("id", "title", "month", "status", "theme");

    @Test
    void declarationOrderSurvivesCompileAndCanonicalRoundTripWhileFieldsStaySorted() throws Exception {
        CompiledModel compiled = compile();
        CompiledConcept challenge = concept(compiled, "Challenge");

        assertEquals(List.of("id", "month", "status", "theme", "title"), names(challenge.getFields()),
                "getFields() keeps the canonical name-sorted layout");
        assertEquals(DECLARED, challenge.getFieldOrder());
        assertEquals(DECLARED, names(challenge.getFieldsInDeclaredOrder()));

        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertEquals(DECLARED, names(concept(roundTripped, "Challenge").getFieldsInDeclaredOrder()));
    }

    @Test
    void inheritedFieldsComeFirstThenTheSpecializationsOwnInDeclaredOrder() throws Exception {
        CompiledConcept sponsored = concept(compile(), "SponsoredChallenge");
        assertEquals(List.of("id", "title", "month", "status", "theme", "sponsor", "budget"),
                names(sponsored.getFieldsInDeclaredOrder()));
    }

    @Test
    void fieldsMissingFromFieldOrderFollowInListOrderAndAnEmptyOrderMeansTheListOrder() {
        List<CompiledField> fields = List.of(
                new CompiledField("a", "string", "String", false, false, false),
                new CompiledField("b", "string", "String", false, false, false),
                new CompiledField("id", "uuid", "UUID", true, true, true));
        CompiledConcept withOrder = new CompiledConcept("X", "X", "x", fields, List.of(), List.of(), null, null, null,
                null, List.of(), null, null, null, null, false, false, null, List.of(), List.of("id", "b"));
        assertEquals(List.of("id", "b", "a"), names(withOrder.getFieldsInDeclaredOrder()));

        CompiledConcept legacy = new CompiledConcept("X", "X", "x", fields);
        assertEquals(List.of("a", "b", "id"), names(legacy.getFieldsInDeclaredOrder()));
    }

    private static CompiledModel compile() throws Exception {
        Path model = Files.createTempFile("npdev-field-order-", ".json");
        Files.writeString(model, MODEL, StandardCharsets.UTF_8);
        return new ModelCompiler().compile(new JsonModelParser().parse(model));
    }

    private static CompiledConcept concept(CompiledModel model, String name) {
        return model.getConcepts().stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow();
    }

    private static List<String> names(List<CompiledField> fields) {
        return fields.stream().map(CompiledField::getName).toList();
    }
}
