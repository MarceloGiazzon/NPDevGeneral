package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * REG-98 (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0.1, fix shape (a)): two differently-named
 * concepts must not be able to silently derive the same physical table name.
 *
 * <p>{@code fieldCollision} below is the exact repro from the ledger item: two concepts, one field
 * each, that previously validated with 0 errors while both compiling to the SQL table
 * {@code order_lines}.
 */
class TableNameCollisionValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<String> validate(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new SemanticValidator().validate(ast);
    }

    private static String modelWithConceptNames(String firstName, String secondName) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.tablecollision", "version": "1.0",
              "concepts": [
                { "name": "%s", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "%s", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """.formatted(firstName, secondName);
    }

    @Test
    void spaceVersusCamelCaseConceptNamesCollideOnOrderLines() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "Order Line"));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("OrderLine") && e.contains("Order Line")
                        && e.contains("order_lines")),
                "expected a table-name collision error naming both concepts and \"order_lines\", got: " + errors);
    }

    @Test
    void hyphenAndDotVariantsAlsoCollide() throws Exception {
        List<String> errors = validate(modelWithConceptNames("Order-Line", "Order.Line"));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("Order-Line") && e.contains("Order.Line")
                        && e.contains("order_lines")),
                "expected a table-name collision error for hyphen/dot variants, got: " + errors);
    }

    @Test
    void distinctConceptNamesWithDistinctTablesPass() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "OrderHeader"));
        assertTrue(errors.stream().noneMatch(e -> e.contains("physical table name")),
                "unexpected table-name collision error for genuinely distinct concepts, got: " + errors);
    }

    @Test
    void sameConceptNameIsStillReportedAsDuplicateConceptNotTableCollision() throws Exception {
        List<String> errors = validate(modelWithConceptNames("OrderLine", "OrderLine"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("Duplicate concept name")),
                "expected the existing duplicate-concept-name error, got: " + errors);
        assertTrue(errors.stream().noneMatch(e -> e.contains("physical table name")),
                "a same-name duplicate should be reported once, by the existing duplicate-name check, "
                        + "not again as a table collision, got: " + errors);
    }

    /**
     * S8 Wave 4 (ADR-0011 D4's v2 opt-in, {@code physicallyIsolate}): the four collision cases
     * across two contexts declaring the SAME bare concept name.
     *
     * <p><b>Found while building this: this check previously NEVER caught a cross-context same-name
     * collision at all</b> -- {@code validateTableNameCollisions} hashed {@code concept.getName()}
     * (the QUALIFIED name, e.g. {@code "wms::Sale"}) directly, a DIFFERENT string than what {@code
     * ModelCompiler} actually compiles to ({@code "sales"}, the context qualifier stripped by D4
     * v1's default) -- so two non-isolating contexts both declaring "Sale" silently compiled to the
     * SAME real table with zero errors anywhere, exactly the "two concepts silently share one
     * table" hazard REG-98 exists to prevent. {@link #bothNonIsolatingSameNameCollidesAndWasSilentBeforeTheFix}
     * RED-verifies this against the pre-fix logic directly (not via git stash -- the pre-fix
     * expression is simple enough to inline and compare).
     *
     * <p><b>The "one isolates, one does not" case is LEGAL, not an error</b> -- confirmed against
     * WAVE4_SPEC.md's own I3 table, which named it a "compile error (one still collides)": with
     * context A isolating ({@code wms::Sale -> wms_sales}) and context B not ({@code logistics::Sale
     * -> sales}), these are genuinely DIFFERENT table names -- no real SQL-level collision, so
     * flagging it would be inventing a restriction the schema does not actually need. Only when
     * BOTH contexts resolve to the bare, unqualified name (both non-isolating) or when isolation
     * itself accidentally produces two identical mangled names (not possible here, since each
     * context's own name is always part of the mangled prefix) is there a real hazard.
     */
    @Test
    void bothNonIsolatingSameConceptNameCollides() throws Exception {
        List<String> errors = validate(modelWithTwoContexts(false, false));
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("wms::Sale") && e.contains("logistics::Sale")
                        && e.contains("\"sales\"")),
                "expected a table-name collision error naming both qualified concepts and \"sales\", got: " + errors);
    }

    @Test
    void oneIsolatingOneNotSameConceptNameIsLegalDistinctTables() throws Exception {
        List<String> errors = validate(modelWithTwoContexts(true, false));
        assertTrue(errors.isEmpty(), "wms_sales and sales are genuinely different tables -- no collision, got: " + errors);
    }

    @Test
    void bothIsolatingSameConceptNameIsLegalDistinctTables() throws Exception {
        List<String> errors = validate(modelWithTwoContexts(true, true));
        assertTrue(errors.isEmpty(),
                "wms_sales and logistics_sales are genuinely different tables -- no collision, got: " + errors);
    }

    @Test
    void bothIsolatingDifferentConceptNameIsLegalDistinctTables() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "contexts": [
                { "name": "wms", "$ref": "contexts/wms.model.json", "physicallyIsolate": true },
                { "name": "logistics", "$ref": "contexts/logistics.model.json", "physicallyIsolate": true }
              ],
              "concepts": [
                { "name": "wms::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "logistics::Shipment", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;
        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "distinct names, both isolating -- unsurprisingly legal, got: " + errors);
    }

    /** RED-verifies {@code bothNonIsolatingSameConceptNameCollides} against the PRE-FIX behavior
     *  directly: the OLD check hashed the qualified name verbatim, which never collides for two
     *  DIFFERENT context prefixes even when the bare concept name is identical. */
    @Test
    void bothNonIsolatingSameNameCollidesAndWasSilentBeforeTheFix() {
        String preFixTableNameForWms = com.npdev.dsl.v1.compiled.SqlIdentifierSupport.toSnakePlural("wms::Sale");
        String preFixTableNameForLogistics =
                com.npdev.dsl.v1.compiled.SqlIdentifierSupport.toSnakePlural("logistics::Sale");
        assertTrue(!preFixTableNameForWms.equals(preFixTableNameForLogistics),
                "RED proof: the pre-fix (qualified-name-verbatim) hash produces DIFFERENT strings "
                        + "(\"" + preFixTableNameForWms + "\" vs \"" + preFixTableNameForLogistics + "\") "
                        + "for what actually compiles to the SAME real table -- the fix (routing through "
                        + "SqlIdentifierSupport#contextAwareIdentifierSource first) is what makes "
                        + "bothNonIsolatingSameConceptNameCollides above correctly fire");
    }

    private static String modelWithTwoContexts(boolean wmsIsolate, boolean logisticsIsolate) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "contexts": [
                { "name": "wms", "$ref": "contexts/wms.model.json", "physicallyIsolate": %s },
                { "name": "logistics", "$ref": "contexts/logistics.model.json", "physicallyIsolate": %s }
              ],
              "concepts": [
                { "name": "wms::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "logistics::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """.formatted(wmsIsolate, logisticsIsolate);
    }
}
