package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-313 (a): {@code computeValue} only does numeric add/subtract -- the kernel fails a step whose
 * operand is not a number with {@code COMPUTE_VALUE_INVALID_OPERAND}. The validator must refuse the
 * operands it can prove non-numeric at model time ("accepted implies enforced"): a literal that is
 * not a number, and {@code $step.field} where {@code step} is a {@code readConcept} of a concept whose
 * {@code field} is declared date/datetime/boolean/uuid/object/array/file.
 */
class ComputeValueOperandValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CODE = "COMPUTE_VALUE_INVALID_OPERAND";

    private static String modelJson(String stepsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "trial.visits", "version": "1.0",
              "concepts": [
                { "name": "Participant", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "enrolmentDate", "type": "date" },
                  { "name": "enrolledAt", "type": "datetime" },
                  { "name": "withdrawn", "type": "boolean" },
                  { "name": "notes", "type": "string" } ] },
                { "name": "VisitType", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "dayOffset", "type": "int" },
                  { "name": "weight", "type": "decimal" } ] }
              ],
              "procedures": [
                { "name": "PlanVisit", "steps": [ %s ] }
              ]
            }
            """.formatted(stepsJson);
    }

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static final String READ_PARTICIPANT = """
        { "name": "read-p", "type": "readConcept", "concept": "Participant",
          "id": "$input.participantId", "target": "p" },
        """;
    private static final String READ_VISIT_TYPE = """
        { "name": "read-vt", "type": "readConcept", "concept": "VisitType",
          "id": "$input.visitTypeId", "target": "vt" },
        """;

    private static void assertRejected(List<String> errors, String... fragments) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(CODE)
                        && java.util.Arrays.stream(fragments).allMatch(e::contains)),
                "expected a " + CODE + " error mentioning " + List.of(fragments) + ", got: " + errors);
    }

    private static void assertNoOperandError(List<String> errors) {
        assertTrue(errors.stream().noneMatch(e -> e.contains(CODE)), "unexpected errors: " + errors);
    }

    @Test
    void addingADayOffsetToADateFieldIsRejected() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + READ_VISIT_TYPE + """
            { "name": "target", "type": "computeValue", "operation": "add",
              "left": "$p.enrolmentDate", "right": "$vt.dayOffset", "target": "targetDate" }
            """));
        assertRejected(errors, "left", "Participant.enrolmentDate", "date");
    }

    @Test
    void theDiagnosticCarriesAStructuredCodeNotTheGenericSemanticOne() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(modelJson(READ_PARTICIPANT + READ_VISIT_TYPE + """
            { "name": "target", "type": "computeValue", "operation": "add",
              "left": "$p.enrolmentDate", "right": "$vt.dayOffset", "target": "targetDate" }
            """)));
        List<ValidationDiagnostic> found = new SemanticValidator().validateWithWarnings(ast).getDiagnostics().stream()
                .filter(d -> d.getMessage().contains(CODE))
                .toList();
        assertEquals(1, found.size(), "expected exactly one operand diagnostic, got: " + found);
        ValidationDiagnostic diagnostic = found.get(0);
        assertEquals("compute_value_invalid_operand", diagnostic.getCode());
        assertEquals("procedures[PlanVisit].steps[2]", diagnostic.getPath());
        assertEquals("validation.semantic.compute_value_invalid_operand", diagnostic.getHelpKey());
        assertTrue(diagnostic.getSuggestedFix().contains("numeric field"), diagnostic.getSuggestedFix());
    }

    @Test
    void aDatetimeFieldOnTheRightIsRejected() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + READ_VISIT_TYPE + """
            { "name": "x", "type": "computeValue", "operation": "subtract",
              "left": "$vt.dayOffset", "right": "$p.enrolledAt", "target": "y" }
            """));
        assertRejected(errors, "right", "Participant.enrolledAt", "datetime");
    }

    @Test
    void aBooleanFieldIsRejected() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": "$p.withdrawn", "right": 1, "target": "y" }
            """));
        assertRejected(errors, "left", "Participant.withdrawn", "boolean");
    }

    @Test
    void aNonNumericLiteralIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": "2026-10-01", "right": 84, "target": "y" }
            """));
        assertRejected(errors, "left", "2026-10-01");
    }

    @Test
    void aBooleanLiteralIsRejected() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": 1, "right": true, "target": "y" }
            """));
        assertRejected(errors, "right", "true");
    }

    @Test
    void anEscapedDollarLiteralIsAStringNotAReference() throws Exception {
        List<String> errors = validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": "$$5", "right": 1, "target": "y" }
            """));
        assertRejected(errors, "left");
    }

    @Test
    void anOperandInsideANestedBranchIsStillChecked() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "gate", "type": "if", "condition": "$input.go",
              "then": [ { "name": "x", "type": "computeValue", "operation": "add",
                          "left": "$p.enrolmentDate", "right": 1, "target": "y" } ] }
            """));
        assertRejected(errors, "then", "Participant.enrolmentDate");
    }

    @Test
    void numericFieldsAndNumericLiteralsPass() throws Exception {
        List<String> errors = validate(modelJson(READ_VISIT_TYPE + """
            { "name": "a", "type": "computeValue", "operation": "add",
              "left": "$vt.dayOffset", "right": "$vt.weight", "target": "sum" },
            { "name": "b", "type": "computeValue", "operation": "subtract",
              "left": "$sum", "right": 7, "target": "diff" },
            { "name": "c", "type": "computeValue", "operation": "add",
              "left": "12.5", "right": 1, "target": "lit" }
            """));
        assertNoOperandError(errors);
    }

    @Test
    void referencesTheValidatorCannotTypeAreLeftToRuntime() throws Exception {
        // $input.* is untyped at model time; a string field may hold digits; an undeclared field and an
        // unknown step are other validators' concern. None may be rejected here (no false positives).
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "a", "type": "computeValue", "operation": "add",
              "left": "$input.days", "right": "$p.notes", "target": "x" },
            { "name": "b", "type": "computeValue", "operation": "add",
              "left": "$p.noSuchField", "right": "$ghost.enrolmentDate", "target": "y" }
            """));
        assertNoOperandError(errors);
    }

    // ---- REG-313 (b): addDays / daysBetween / today are computeValue operations too ----

    @Test
    void addDaysOfADateFieldAndADayCountIsTheRepro_SpelledRight() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + READ_VISIT_TYPE + """
            { "name": "target", "type": "computeValue", "operation": "addDays",
              "left": "$p.enrolmentDate", "right": "$vt.dayOffset", "target": "targetDate" },
            { "name": "ws", "type": "computeValue", "operation": "addDays",
              "left": "$targetDate", "right": -7, "target": "windowStart" },
            { "name": "gap", "type": "computeValue", "operation": "daysBetween",
              "left": "$p.enrolmentDate", "right": "2026-12-24", "target": "gap" },
            { "name": "when", "type": "computeValue", "operation": "today", "target": "now" }
            """));
        assertEquals(List.of(), errors);
    }

    @Test
    void addDaysWantsADateOnTheLeft() throws Exception {
        List<String> errors = validate(modelJson(READ_VISIT_TYPE + """
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "$vt.dayOffset", "right": 5, "target": "y" }
            """));
        assertRejected(errors, "left", "VisitType.dayOffset", "int", "date");
    }

    @Test
    void addDaysWantsANumberOfDaysNotADateOnTheRight() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "$p.enrolmentDate", "right": "$p.enrolledAt", "target": "y" }
            """));
        assertRejected(errors, "right", "Participant.enrolledAt", "datetime");
    }

    @Test
    void addDaysLiteralsMustBeADateAndAWholeNumber() throws Exception {
        assertRejected(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "soon", "right": 1, "target": "y" }
            """)), "left", "soon");
        assertRejected(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "2026-10-01", "right": 1.5, "target": "y" }
            """)), "right", "1.5", "whole");
        assertRejected(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "2026-10-01", "right": "3", "target": "y" }
            """)), "right", "3", "whole");
        assertNoOperandError(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "addDays",
              "left": "2026-10-01T10:15:30Z", "right": 3, "target": "y" }
            """)));
    }

    @Test
    void daysBetweenWantsTwoDates() throws Exception {
        assertRejected(validate(modelJson(READ_PARTICIPANT + READ_VISIT_TYPE + """
            { "name": "x", "type": "computeValue", "operation": "daysBetween",
              "left": "$p.enrolmentDate", "right": "$vt.dayOffset", "target": "y" }
            """)), "right", "VisitType.dayOffset", "int");
        assertRejected(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "daysBetween",
              "left": "2026-10-01", "right": 84, "target": "y" }
            """)), "right", "84");
    }

    @Test
    void plainAddOfADateNowPointsAtAddDays() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": "$p.enrolmentDate", "right": 84, "target": "y" }
            """));
        assertRejected(errors, "left", "Participant.enrolmentDate", "addDays");
    }

    @Test
    void todayTakesNoOperandsAndStillNeedsATarget() throws Exception {
        assertTrue(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "today", "left": "2026-10-01", "target": "y" }
            """)).stream().anyMatch(e -> e.contains("today takes no operands")));
        assertTrue(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "today" }
            """)).stream().anyMatch(e -> e.contains("target is required for computeValue")));
    }

    @Test
    void anOperatorIsStillCaseInsensitiveAndAnUnknownOneStillRefused() throws Exception {
        assertNoOperandError(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": " AddDays ",
              "left": "2026-10-01", "right": 1, "target": "y" }
            """)));
        assertTrue(validate(modelJson("""
            { "name": "x", "type": "computeValue", "operation": "addMonths",
              "left": "2026-10-01", "right": 1, "target": "y" }
            """)).stream().anyMatch(e -> e.contains("computeValue requires operation to be one of")
                && e.contains("addDays") && e.contains("daysBetween") && e.contains("today")));
    }

    @Test
    void aTargetBoundTwiceToDifferentConceptsIsAmbiguousSoSkipped() throws Exception {
        List<String> errors = validate(modelJson(READ_PARTICIPANT + """
            { "name": "re-read", "type": "readConcept", "concept": "VisitType",
              "id": "$input.visitTypeId", "target": "p" },
            { "name": "x", "type": "computeValue", "operation": "add",
              "left": "$p.enrolmentDate", "right": 1, "target": "y" }
            """));
        assertNoOperandError(errors);
    }
}
