package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * S3 (B20 part 2, docs/adr/ADR-0011-bounded-contexts.md addendum): D4 ("no physical table
 * prefixing") was accepted in the ADR but never implemented -- {@code ModelSourceResolver} always
 * qualifies a context's concepts {@code contextName::Name} (D1), and
 * {@code SqlIdentifierSupport.toSnake} folded that qualifier straight into the table name
 * ({@code ::} -> {@code _}), identical to how PACK-qualified names are meant to behave. Nothing
 * caught this because {@code BoundedContextResolutionTest} only asserts JSON-level resolution, never
 * compiled table names. RED/GREEN for the fix in {@code ModelCompiler.tableNameSource}.
 *
 * <p>These tests feed {@link JsonModelParser} an already-resolved document (a context's concepts
 * pre-qualified {@code context::Name}, {@code contexts[]} as the pure {name, $ref} registry) --
 * exactly the shape {@code ModelSourceResolver} would have produced -- since {@code ContextAst}'s own
 * contract states the fragment is already composed and qualified by the time parsing sees it.
 */
class BoundedContextTableNamingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    private static CompiledConcept conceptNamed(CompiledModel model, String name) {
        return model.getConcepts().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No concept named " + name + " in " + model.getConcepts()));
    }

    @Test
    void contextQualifiedConceptGetsBareTableName() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "contexts": [ { "name": "wms", "$ref": "contexts/wms.model.json" } ],
              "concepts": [
                { "name": "wms::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledConcept sale = conceptNamed(model, "wms::Sale");

        assertEquals("sales", sale.getTableName(), "D4: a context qualifier must not reach the table name");
        assertEquals("WmsSale", sale.getClassName(), "class-name mangling is unaffected by D4 -- only tables");
    }

    @Test
    void physicallyIsolatingContextKeepsQualifiedMangledTableName() throws Exception {
        // S8 Wave 4 (ADR-0011 D4's own named v2 escape): a context declaring physicallyIsolate:true
        // opts OUT of D4's default -- its table name keeps the context qualifier, mangled by the
        // SAME "::" -> "_" replacement pack-qualified names already get.
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "contexts": [ { "name": "wms", "$ref": "contexts/wms.model.json", "physicallyIsolate": true } ],
              "concepts": [
                { "name": "wms::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledConcept sale = conceptNamed(model, "wms::Sale");

        assertEquals("wms_sales", sale.getTableName(), "physicallyIsolate:true keeps the context qualifier");
        assertEquals("WmsSale", sale.getClassName(), "class-name mangling is unaffected either way -- only tables");
    }

    @Test
    void physicallyIsolateExplicitFalseBehavesExactlyLikeAbsent() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms", "version": "1.0",
              "contexts": [ { "name": "wms", "$ref": "contexts/wms.model.json", "physicallyIsolate": false } ],
              "concepts": [
                { "name": "wms::Sale", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledConcept sale = conceptNamed(model, "wms::Sale");

        assertEquals("sales", sale.getTableName(), "explicit physicallyIsolate:false is D4's unchanged default");
    }

    @Test
    void packQualifiedConceptStillGetsPrefixedTableName() throws Exception {
        // Regression guard: D4 is scoped to CONTEXTS only. A pack-qualified concept name must keep
        // prefixing its table exactly as it always has -- this fix must not touch that behavior.
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "app", "version": "1.0",
              "concepts": [
                { "name": "billing::Invoice", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledConcept invoice = conceptNamed(model, "billing::Invoice");

        assertEquals("billing_invoices", invoice.getTableName(),
                "pack-qualified table prefixing must be unaffected by the D4 fix");
    }
}
