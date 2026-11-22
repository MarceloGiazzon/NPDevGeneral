package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Four small switches an author writes and the app must honour: a concept whose form opens as a pop-up, a field kept
 * out of the generated web page, an inherited rule replaced by a stricter one, and a setting flagged as security
 * relevant. Each is a keyword no other unit test names, and each is silently lost if the parser or compiler forgets it.
 */
class FormSwitchesAndOverridesSurviveCompileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
        {
          "dslVersion": "3.0.0", "namespace": "demo.switches", "version": "1.0",
          "properties": [ { "name": "refundLimit", "type": "int", "default": 100, "securityRelevant": true } ],
          "concepts": [
            { "name": "Ticket",
              "ui": { "label": "Ticket", "formPresentation": "modal" },
              "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true },
                { "name": "subject", "type": "string", "required": true, "ui": { "label": "Subject" } },
                { "name": "internalNote", "type": "string", "required": false,
                  "ui": { "label": "Internal note", "showInDefaultWebUi": false } }
              ],
              "invariants": [ { "name": "SubjectFilled", "expr": "subject != ''" } ] },
            { "name": "UrgentTicket", "specializes": "Ticket",
              "ui": { "label": "Urgent ticket" },
              "fields": [ { "name": "deadline", "type": "date", "required": false, "ui": { "label": "Deadline" } } ],
              "invariants": [ { "name": "SubjectFilled", "specializes": "SubjectFilled", "override": true,
                                "expr": "subject != '' && subject != 'TBD'" } ] }
          ]
        }
        """;

    private static JsonNode canonical() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(MODEL));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), errors.toString());
        CompiledModel compiled = new ModelCompiler().compile(ast);
        return MAPPER.readTree(CompiledModelCanonicalJson.toJson(compiled));
    }

    /** Every value the canonical JSON holds under {@code key}, wherever it sits in the tree. */
    private static List<JsonNode> valuesOf(JsonNode node, String key) {
        List<JsonNode> found = new ArrayList<>();
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (entry.getKey().equals(key)) {
                    found.add(entry.getValue());
                }
                found.addAll(valuesOf(entry.getValue(), key));
            });
        } else if (node.isArray()) {
            node.forEach(child -> found.addAll(valuesOf(child, key)));
        }
        return found;
    }

    @Test
    void aConceptWhoseFormOpensAsAPopUpKeepsItsFormPresentation() throws Exception {
        assertTrue(valuesOf(canonical(), "formPresentation").stream().anyMatch(v -> "modal".equals(v.asText())),
                "formPresentation modal must reach the canonical JSON");
    }

    @Test
    void aFieldHiddenFromTheGeneratedWebPageKeepsItsSwitch() throws Exception {
        assertTrue(valuesOf(canonical(), "showInDefaultWebUi").stream().anyMatch(v -> v.isBoolean() && !v.asBoolean()),
                "showInDefaultWebUi false must reach the canonical JSON");
    }

    @Test
    void anInheritedRuleReplacedByAStricterOneIsAcceptedAndItsStricterTextIsKept() throws Exception {
        // the flag itself is consumed when the compiler merges the specialisation (a same-name rule without it would be
        // a validation error, which canonical() would have failed on); what survives is the replacing expression
        assertTrue(valuesOf(canonical(), "expression").stream()
                        .anyMatch(v -> "subject != '' && subject != 'TBD'".equals(v.asText())),
                "the replacing invariant expression must reach the canonical JSON");
    }

    @Test
    void aSettingFlaggedSecurityRelevantKeepsTheFlag() throws Exception {
        assertTrue(valuesOf(canonical(), "securityRelevant").stream().anyMatch(JsonNode::asBoolean),
                "securityRelevant true must reach the canonical JSON");
    }
}
