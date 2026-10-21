package com.finalexec.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptAggregateQuery;
import com.npdev.kernel.concepts.ConceptAggregateRequest;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptWriteRequest;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REP-1: the layout/total rules of a {@code kind: list} document, plus the security finding that
 * shaped the design -- the report is computed in Java over {@code ConceptGateway.query()} rows,
 * because {@code ConceptGateway.aggregate()} does not remove fields the viewer may not read.
 */
class ListReportTest {

    private static final String MODEL = """
            {
              "dslVersion": "1.0.0", "namespace": "hr", "version": "1.0",
              "concepts": [
                { "name": "Payroll", "fields": [
                    { "name": "id", "type": "string", "id": true, "required": true },
                    { "name": "dept", "type": "string" },
                    { "name": "employee", "type": "string" },
                    { "name": "ownerId", "type": "string" },
                    { "name": "salary", "type": "decimal",
                      "access": { "read": "ownerId == $user.id" } } ] },
                { "name": "Expense", "fields": [
                    { "name": "id", "type": "string", "id": true, "required": true },
                    { "name": "ownerId", "type": "string" },
                    { "name": "category", "type": "string" },
                    { "name": "amount", "type": "integer" } ],
                  "access": { "read": "ownerId == $user.id" } }
              ],
              "documents": [
                { "name": "SalaryByDept", "concept": "Payroll", "kind": "list", "title": "Salary by department",
                  "columns": [ { "field": "employee", "label": "Employee" }, { "field": "dept" },
                               { "field": "salary", "label": "Salary", "total": "sum" } ],
                  "groupBy": ["dept"], "subtotals": true, "grandTotal": true },
                { "name": "SpendByCategory", "concept": "Expense", "kind": "list",
                  "columns": [ { "field": "category" }, { "field": "amount", "total": "sum" } ],
                  "groupBy": ["category"], "subtotals": true, "grandTotal": true }
              ]
            }
            """;

    private static CompiledModel model() throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
    }

    private static CompiledDocument document(CompiledModel model, String name) {
        return model.getDocuments().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    private static ExecutionContext user(String actorId) {
        return new ExecutionContext("t1", actorId, Map.of(), Set.of("USER"));
    }

    private static List<ConceptRecord> queryAll(DefaultConceptGateway gateway, String concept, ExecutionContext who) {
        ConceptPage page = gateway.query(new ConceptQueryRequest(concept, "t1",
                new ConceptQuery(List.of(), List.of(), 0, ConceptQuery.MAX_LIMIT)), who);
        return new ArrayList<>(page.items());
    }

    private static void seed(DefaultConceptGateway gateway) {
        ExecutionContext system = ExecutionContext.system("t1");
        String[][] payroll = {
                {"p1", "Eng", "Ana", "alice", "100"}, {"p2", "Eng", "Bob", "bob", "200"},
                {"p3", "Ops", "Cy", "alice", "30.5"}, {"p4", "Ops", "Di", "bob", "40"}};
        for (String[] row : payroll) {
            gateway.save(new ConceptWriteRequest("Payroll", row[0], "t1", Map.of(
                    "id", row[0], "dept", row[1], "employee", row[2], "ownerId", row[3],
                    "salary", new java.math.BigDecimal(row[4]))), system);
        }
        Object[][] expenses = {
                {"e1", "alice", "Travel", 10}, {"e2", "alice", "Travel", 5}, {"e3", "alice", "Food", 7},
                {"e4", "bob", "Travel", 1000}, {"e5", "bob", "Food", 2000}};
        for (Object[] row : expenses) {
            gateway.save(new ConceptWriteRequest("Expense", (String) row[0], "t1", Map.of(
                    "id", row[0], "ownerId", row[1], "category", row[2], "amount", row[3])), system);
        }
    }

    // ---------------------------------------------------------------- layout

    @Test
    void groupsSubtotalsAndGrandTotalAreComputedOverTheRowsGiven() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);

        // alice: row rule keeps only her 3 expenses
        ListReport report = ListReport.build(document(model, "SpendByCategory"),
                queryAll(gateway, "Expense", user("alice")));

        assertEquals(3, report.rowCount());
        List<String> kinds = report.lines().stream().map(l -> l.type().name()).toList();
        assertEquals(List.of("GROUP_HEADER", "DETAIL", "SUBTOTAL", "GROUP_HEADER", "DETAIL", "DETAIL", "SUBTOTAL",
                "GRAND_TOTAL"), kinds);
        // groups sorted by key: Food before Travel
        assertEquals("category: Food", report.lines().get(0).label());
        assertEquals(List.of("Subtotal Food", "7"), report.lines().get(2).cells());
        assertEquals(List.of("Subtotal Travel", "15"), report.lines().get(6).cells());
        // 22, NOT 3022: bob's 1000 and 2000 must not be in anything alice sees
        assertEquals(List.of("Grand total", "22"), report.lines().get(7).cells());
    }

    @Test
    void csvMirrorsTheLayoutAndNeutralizesFormulaLeads() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);
        gateway.save(new ConceptWriteRequest("Expense", "e9", "t1", Map.of(
                "id", "e9", "ownerId", "alice", "category", "=HYPERLINK(\"http://x\")", "amount", 1)),
                ExecutionContext.system("t1"));

        String csv = ListReport.build(document(model, "SpendByCategory"),
                queryAll(gateway, "Expense", user("alice"))).toCsv();
        String[] lines = csv.split("\r\n");

        assertEquals("category,amount", lines[0]);
        assertTrue(csv.contains("Subtotal Food,7\r\n"), csv);
        assertTrue(csv.endsWith("Grand total,23\r\n"), csv);
        // the formula-looking category value is defused, in the cell AND in the group header / subtotal text
        assertFalse(csv.contains("\n=HYPERLINK"), csv);
        assertTrue(csv.contains("'=HYPERLINK"), csv);
    }

    @Test
    void htmlIsWellFormedXmlAndEscapesValues() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);
        gateway.save(new ConceptWriteRequest("Expense", "e8", "t1", Map.of(
                "id", "e8", "ownerId", "alice", "category", "<b>&\"x\"", "amount", 3)), ExecutionContext.system("t1"));

        String html = ListReport.build(document(model, "SpendByCategory"),
                queryAll(gateway, "Expense", user("alice"))).toHtml("Spend <report>", "2026-10-20 10:00");

        Document dom = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(dom.getDocumentElement());
        assertTrue(html.contains("Spend &lt;report&gt;"));
        assertTrue(html.contains("&lt;b&gt;&amp;&quot;x&quot;"));
        assertTrue(html.contains("<tr class=\"grand-total\">"));
        assertTrue(html.contains("Total: 4 record(s)"));
    }

    @Test
    void noRowsPrintsDeclaredColumnsAndNoTotals() throws Exception {
        CompiledModel model = model();
        ListReport report = ListReport.build(document(model, "SpendByCategory"), List.of());
        assertEquals(2, report.columns().size());
        assertTrue(report.lines().isEmpty());
        assertTrue(report.toHtml("t", "now").contains("No records"));
        assertEquals("category,amount\r\n", report.toCsv());
    }

    @Test
    void absurdStoredNumbersAreNotSummed() throws Exception {
        CompiledModel model = model();
        List<ConceptRecord> rows = List.of(
                new ConceptRecord("Expense", "x1", "t1", Map.of("category", "A", "amount", "1E+999999999")),
                new ConceptRecord("Expense", "x2", "t1", Map.of("category", "A", "amount", 5)));
        ListReport report = ListReport.build(document(model, "SpendByCategory"), rows);
        // the oversized value is ignored by the total, not allowed to blow up the sum
        assertEquals("5", report.lines().get(report.lines().size() - 1).cells().get(1));
    }

    // ------------------------------------------- hidden fields and the aggregate() leak

    @Test
    void hiddenFieldIsAbsentAndNeverSummedWhenTheViewerMayNotReadItOnAnyRow() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);

        // carol owns no Payroll row, so field access.read hides salary on every row she gets back
        List<ConceptRecord> rows = queryAll(gateway, "Payroll", user("carol"));
        assertEquals(4, rows.size());
        assertTrue(rows.stream().noneMatch(r -> r.data().containsKey("salary")));

        ListReport report = ListReport.build(document(model, "SalaryByDept"), rows);
        List<String> labels = report.columns().stream().map(ListReport.Column::label).toList();
        assertEquals(List.of("Employee", "dept"), labels);                 // Salary column omitted
        String csv = report.toCsv();
        for (String leaked : List.of("100", "200", "30.5", "40", "370.5", "Salary")) {
            assertFalse(csv.contains(leaked), "hidden salary figure leaked into CSV: " + leaked + "\n" + csv);
        }
        // no total column is left, so there is nothing to subtotal: no empty total rows either
        assertTrue(report.lines().stream().noneMatch(l ->
                l.type() == ListReport.LineType.SUBTOTAL || l.type() == ListReport.LineType.GRAND_TOTAL));
    }

    @Test
    void totalsCoverOnlyTheRowsWhereTheFieldIsVisible() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);

        // alice may read salary on HER rows only (p1=100, p3=30.5): field access.read is per record
        ListReport report = ListReport.build(document(model, "SalaryByDept"), queryAll(gateway, "Payroll", user("alice")));
        String csv = report.toCsv();
        assertTrue(csv.contains("Subtotal Eng,,100\r\n"), csv);
        assertTrue(csv.contains("Subtotal Ops,,30.5\r\n"), csv);
        assertTrue(csv.endsWith("Grand total,,130.5\r\n"), csv);   // not 370.5
        assertTrue(csv.contains("Bob,Eng,\r\n"), csv);   // bob's salary: blank cell, absent from the sum
    }

    /**
     * The finding itself. {@code aggregate()} pushes the group-by down WITHOUT stripping fields the
     * viewer may not read, so a grouped sum over a hidden field hands back a number derived from it.
     * If this assertion ever turns red the gateway was fixed and the report may be revisited; until
     * then the report MUST keep computing over query() rows (the two tests above).
     */
    @Test
    void aggregateWouldLeakWhatTheQueryBasedReportDoesNot() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);
        ExecutionContext carol = user("carol");

        ConceptAggregateResult leaked = gateway.aggregate(new ConceptAggregateRequest("Payroll",
                new ConceptAggregateQuery(List.of(),
                        List.of(new ConceptAggregateQuery.GroupByField("dept", null)),
                        List.of(new ConceptAggregateQuery.AggregateFunction("total", "sum", "salary")),
                        List.of(), List.of(), null)), carol);
        boolean gatewayLeaks = leaked.rows().stream().anyMatch(row -> row.toString().contains("130")
                || row.toString().contains("240") || row.toString().contains("70.5"));
        assertTrue(gatewayLeaks, "expected aggregate() to expose the hidden salary sum; got " + leaked.rows());

        String reportCsv = ListReport.build(document(model, "SalaryByDept"),
                queryAll(gateway, "Payroll", carol)).toCsv();
        assertFalse(reportCsv.contains("130") || reportCsv.contains("240") || reportCsv.contains("70.5"), reportCsv);
    }

    @Test
    void rowScopeAppliesBeforeAnyTotal() throws Exception {
        CompiledModel model = model();
        DefaultConceptGateway gateway = DefaultConceptGateway.governedBy(new InMemoryConceptStore(), model);
        seed(gateway);
        ListReport bob = ListReport.build(document(model, "SpendByCategory"), queryAll(gateway, "Expense", user("bob")));
        assertTrue(bob.toCsv().endsWith("Grand total,3000\r\n"), bob.toCsv());
        ListReport nobody = ListReport.build(document(model, "SpendByCategory"), queryAll(gateway, "Expense", user("zed")));
        assertEquals(0, nobody.rowCount());
        assertFalse(nobody.toCsv().contains("Grand total"));
    }
}
