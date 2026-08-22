package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.xref.ReferenceEdge;
import com.npdev.dsl.v1.xref.ReferenceIndex;
import com.npdev.dsl.v1.xref.Resolution;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XREF-1: one case per coverage class, plus the two cases that decide whether this index is
 * trustworthy at all.
 *
 * <p>The first is {@link #orderByNamingAnAggregateAliasIsNotReported()}. A naive probe over the
 * 48-model corpus reported 4 orphans and 3 were this exact false positive -- an {@code orderBy}
 * entry naming a declared {@code aggregates[].name}. An index that produces false positives gets
 * switched off, so this is not a nicety.
 *
 * <p>The second is {@link #inheritedFieldsAreNotReportedAsMissing()}. Fields arrive through
 * {@code extends}; checking a reference against the concept's OWN field list alone would flag
 * every correct reference to an inherited field in the corpus.
 */
class ReferenceIndexTest {

    private static ModelAst parse(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-xref-", ".json");
        Files.writeString(modelPath, json);
        return new JsonModelParser().parse(modelPath);
    }

    private static List<ReferenceEdge> unresolvedOf(ReferenceIndex index) {
        return index.edges().stream()
                .filter(edge -> edge.resolution() == Resolution.UNRESOLVED)
                .collect(Collectors.toList());
    }

    private static String describe(List<ReferenceEdge> edges) {
        return edges.stream().map(e -> e.path() + " -> " + e.toName()).collect(Collectors.joining(", "));
    }

    private static boolean hasUnresolved(ReferenceIndex index, String toName) {
        return unresolvedOf(index).stream().anyMatch(edge -> edge.toName().equals(toName));
    }

    // -- coverage class 1: structural keys with an unambiguous concept ---------------------------

    @Test
    void panelLayoutFieldsAndFieldBindingsAreCrossReferenced() throws Exception {
        // The measured RED: both of these validated `passed` with zero diagnostics.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.panels",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "customerEmail", "type": "string", "ui": { "label": "Email" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["customerEmail", "totallyMadeUpField"] },
                      "fieldBindings": [
                        { "field": "customerEmail", "source": "orders", "editable": true },
                        { "field": "anotherGhostField", "source": "orders", "editable": true }
                      ]
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "WidgetOrder.totallyMadeUpField"),
                "layout.fields ghost not reported: " + describe(unresolvedOf(index)));
        assertTrue(hasUnresolved(index, "WidgetOrder.anotherGhostField"),
                "fieldBindings[].field ghost not reported: " + describe(unresolvedOf(index)));
        assertFalse(hasUnresolved(index, "WidgetOrder.customerEmail"),
                "a real field must not be reported");
    }

    @Test
    void panelActionInputFieldsAreCrossReferenced() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.actions",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["status"] },
                      "actions": [
                        { "name": "Approve", "label": "Approve", "binding": "conceptMutation",
                          "concept": "WidgetOrder", "operation": "update",
                          "inputFields": ["status", "ghostInputField"] }
                      ]
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "WidgetOrder.ghostInputField"),
                "panelAction.inputFields ghost not reported: " + describe(unresolvedOf(index)));
    }

    // -- coverage class 1: queries, the one that reaches SQL --------------------------------------

    @Test
    void queryOrderByGhostFieldIsReported() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.queries",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "queries": [
                    { "name": "GhostQuery", "concept": "WidgetOrder", "orderBy": ["ghostOrderField"] }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "WidgetOrder.ghostOrderField"),
                "query.orderBy reaches SQL and must be reported: " + describe(unresolvedOf(index)));
    }

    @Test
    void orderByNamingAnAggregateAliasIsNotReported() throws Exception {
        // MEASURED false positive: 3 of the naive probe's 4 corpus "orphans" were this.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.aggregatealias",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Shipment", "ui": { "label": "Shipment" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "warehouse", "type": "string", "ui": { "label": "Warehouse" } },
                      { "name": "units", "type": "integer", "ui": { "label": "Units" } }
                    ] }
                  ],
                  "queries": [
                    {
                      "name": "ShippedUnitsByWarehouse",
                      "concept": "Shipment",
                      "groupBy": ["warehouse"],
                      "aggregates": [ { "name": "totalUnits", "fn": "sum", "field": "units" } ],
                      "orderBy": ["totalUnits desc"]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index),
                "an orderBy naming a declared aggregate alias is legal and reaches SQL as the alias");
    }

    @Test
    void trailingSortDirectionIsStrippedBeforeResolving() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.sortdirection",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Shipment", "ui": { "label": "Shipment" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "createdAt", "type": "datetime", "ui": { "label": "Created" } }
                    ] }
                  ],
                  "queries": [
                    { "name": "Recent", "concept": "Shipment", "orderBy": ["createdAt desc"] }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
    }

    // -- coverage class 2: named-object references -----------------------------------------------

    @Test
    void namedObjectReferencesResolveAgainstTheirOwnKind() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.named",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "queries": [ { "name": "AllOrders", "concept": "WidgetOrder" } ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [
                        { "name": "real", "concept": "WidgetOrder", "query": "AllOrders" },
                        { "name": "ghost", "concept": "WidgetOrder", "query": "NoSuchQuery" }
                      ],
                      "layout": { "type": "table", "fields": ["status"] }
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "NoSuchQuery"), describe(unresolvedOf(index)));
        assertFalse(hasUnresolved(index, "AllOrders"));
    }

    @Test
    void aFieldBindingSourceNamingNoDataSourceIsReported() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.dsname",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["status"] },
                      "fieldBindings": [ { "field": "status", "source": "typoedSource", "editable": true } ]
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "typoedSource"), describe(unresolvedOf(index)));
    }

    // -- coverage class 3: expression strings -----------------------------------------------------

    @Test
    void interactionPredicateIdentifiersAreCrossReferenced() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.predicate",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["status"] },
                      "fieldBindings": [
                        { "field": "status", "source": "orders", "editable": true,
                          "visibleWhen": "ghostPredicateField == 'A'" }
                      ]
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "WidgetOrder.ghostPredicateField"), describe(unresolvedOf(index)));
    }

    @Test
    void anExpressionOutsideTheGrammarIsUndecidableNotUnresolved() throws Exception {
        // `$ui.` forms are legal and outside the identifier grammar. Reporting them as orphans
        // would make the checker wrong; reporting them as clean would rebuild REG-185's silence.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.uipredicate",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WidgetOrder", "ui": { "label": "Widget order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "panels": [
                    {
                      "name": "OrdersPanel",
                      "route": "/orders",
                      "dataSources": [ { "name": "orders", "concept": "WidgetOrder" } ],
                      "layout": { "type": "table", "fields": ["status"] },
                      "fieldBindings": [
                        { "field": "status", "source": "orders", "editable": true,
                          "visibleWhen": "$ui.mode == 'edit'" }
                      ]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), "must not be UNRESOLVED: " + describe(unresolvedOf(index)));
        assertTrue(index.unresolved().stream()
                        .anyMatch(edge -> edge.resolution() == Resolution.UNDECIDABLE
                                && edge.toName().contains("$ui.mode")),
                "must be recorded as UNDECIDABLE, not dropped");
    }

    // -- inheritance ------------------------------------------------------------------------------

    @Test
    void inheritedFieldsAreNotReportedAsMissing() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.inheritance",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "BaseDoc", "ui": { "label": "Base doc" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "createdAt", "type": "datetime", "ui": { "label": "Created" } }
                    ] },
                    { "name": "Invoice", "extends": "BaseDoc", "ui": { "label": "Invoice" }, "fields": [
                      { "name": "amountCents", "type": "integer", "ui": { "label": "Amount" } }
                    ] }
                  ],
                  "queries": [
                    { "name": "RecentInvoices", "concept": "Invoice", "orderBy": ["createdAt desc"] }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index),
                "createdAt is inherited from BaseDoc: " + describe(unresolvedOf(index)));
    }

    // -- aggregates ------------------------------------------------------------------------------

    @Test
    void aggregateCollectionChildFieldResolvesAgainstTheCHILDConcept() throws Exception {
        // Getting this backwards (resolving childField against the ROOT) would flag every correct
        // master/detail declaration in the corpus, which is the failure mode that makes an author
        // stop trusting the checker.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.aggregates",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "ui": { "label": "Order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] },
                    { "name": "OrderLine", "ui": { "label": "Order line" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "order", "type": "reference", "reference": { "target": "Order", "via": "id" } }
                    ] }
                  ],
                  "aggregates": [
                    { "name": "OrderAggregate", "root": "Order", "collections": [
                      { "name": "lines", "concept": "OrderLine", "childField": "order" }
                    ] }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
    }

    // -- usagesOf / determinism -------------------------------------------------------------------

    @Test
    void usagesOfAFieldFindsEverySiteThatNamesIt() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.usages",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Patient", "ui": { "label": "Patient" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "birthDay", "type": "date", "ui": { "label": "Birth day" } }
                    ] }
                  ],
                  "queries": [ { "name": "ByBirthday", "concept": "Patient", "orderBy": ["birthDay"] } ],
                  "panels": [
                    {
                      "name": "PatientsPanel",
                      "route": "/patients",
                      "dataSources": [ { "name": "patients", "concept": "Patient" } ],
                      "layout": { "type": "table", "fields": ["birthDay"] },
                      "fieldBindings": [ { "field": "birthDay", "source": "patients", "editable": true } ]
                    }
                  ]
                }
                """));

        List<ReferenceEdge> usages = index.usagesOf("Patient.birthDay");

        assertEquals(3, usages.size(), "query.orderBy + panel.layout.fields + fieldBindings.field: "
                + describe(usages));
        assertTrue(usages.stream().allMatch(edge -> edge.resolution() == Resolution.RESOLVED));
        assertTrue(usages.stream().anyMatch(edge -> edge.site().equals(ReferenceIndex.SITE_QUERY_ORDER_BY)));
        assertTrue(usages.stream().anyMatch(edge -> edge.site().equals(ReferenceIndex.SITE_PANEL_LAYOUT_FIELDS)));
        assertTrue(usages.stream()
                .anyMatch(edge -> edge.site().equals(ReferenceIndex.SITE_PANEL_FIELD_BINDING_FIELD)));
    }

    // -- the five false-positive classes the first corpus sweep found ---------------------------
    //
    // The first run of this index over the 48-model corpus reported 173 unresolved edges. 172 were
    // wrong. Each was a real NPDev rule this walker did not know; each is pinned below, because a
    // cross-reference checker that cries wolf is worse than none -- it gets switched off, and then
    // the real orphan it was built for (REG-185) goes back to being invisible.

    @Test
    void aDottedFieldBindingSourceIsADataSourcePlusField() throws Exception {
        // 160+ of the 173. Most of the corpus writes "source": "pendingExpenses.employeeEmail",
        // and reading that whole string as a data-source NAME orphans every one of them.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.dottedsource",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Expense", "ui": { "label": "Expense" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "employeeEmail", "type": "string", "ui": { "label": "Employee" } }
                    ] }
                  ],
                  "queries": [ { "name": "PendingExpenses", "concept": "Expense" } ],
                  "panels": [
                    {
                      "name": "ExpensePanel",
                      "route": "/expenses",
                      "dataSources": [ { "name": "pending", "query": "PendingExpenses" } ],
                      "layout": { "type": "table", "fields": ["employeeEmail"] },
                      "fieldBindings": [ { "field": "employeeEmail", "source": "pending.employeeEmail" } ]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
    }

    @Test
    void aDataSourceTakesItsConceptFromItsQueryWhenItDeclaresNone() throws Exception {
        // Without this, a query-backed data source leaves the panel with no concept in scope and
        // every field reference on it silently degrades to UNDECIDABLE -- coverage lost, quietly.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.dsconcept",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Task", "ui": { "label": "Task" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "title", "type": "string", "ui": { "label": "Title" } }
                    ] }
                  ],
                  "queries": [ { "name": "OpenTasks", "concept": "Task" } ],
                  "panels": [
                    {
                      "name": "TasksPanel",
                      "route": "/tasks",
                      "dataSources": [ { "name": "open", "query": "OpenTasks" } ],
                      "layout": { "type": "table", "fields": ["title", "ghostColumn"] }
                    }
                  ]
                }
                """));

        assertTrue(hasUnresolved(index, "Task.ghostColumn"),
                "the concept must be inherited from the query, or nothing here is checked at all: "
                        + describe(index.unresolved()));
    }

    @Test
    void aPanelVisibilityRoleExpressionIsNotAFieldReference() throws Exception {
        // superuser-admin-console writes "visibility": "isSuperUser", and
        // RuntimeApiEmitter.addRoleVisibilityPermission reads role:ADMIN out of the same key.
        // Checking it against the concept reported a shipped sample as broken.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.visibility",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Project", "ui": { "label": "Project" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "name", "type": "string", "ui": { "label": "Name" } }
                    ] }
                  ],
                  "procedures": [
                    { "name": "CountProjects", "steps": [ { "name": "done", "type": "return", "value": "1" } ] }
                  ],
                  "panels": [
                    {
                      "name": "ProjectsPanel",
                      "route": "/projects",
                      "visibility": "isSuperUser",
                      "dataSources": [ { "name": "projects", "concept": "Project" } ],
                      "layout": { "type": "table", "fields": ["name"] },
                      "actions": [
                        { "name": "recount", "label": "Recount", "binding": "procedure",
                          "procedure": "CountProjects", "enabledWhen": "role == 'ADMIN'" }
                      ]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
        assertTrue(index.unresolved().stream()
                        .anyMatch(edge -> edge.resolution() == Resolution.UNDECIDABLE
                                && edge.toName().equals("isSuperUser")),
                "recorded, not dropped -- 'we cannot check this' must stay visible");
    }

    @Test
    void aGeneratedActionCapabilityResolvesAgainstTheStepThatDeclaresIt() throws Exception {
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.generatedaction",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "ui": { "label": "Order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true }
                    ] }
                  ],
                  "flows": [
                    {
                      "name": "PlaceOrder",
                      "input": { "concept": "Order", "mode": "create" },
                      "steps": [
                        { "name": "save", "type": "createConcept", "scope": "Order",
                          "input": "$input", "output": "$saved" },
                        { "name": "score", "type": "generatedAction", "actionName": "ScoreOrderRisk",
                          "input": "$saved", "output": "$risk" },
                        { "name": "done", "type": "return", "value": "$saved" }
                      ]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index),
                "generated.action.X names a generated action, not a declared capability: "
                        + describe(unresolvedOf(index)));
    }

    @Test
    void aGroupByJoinPathIsWalkedHopByHop() throws Exception {
        // shipment.invoice.status is a join path (GroupByJoinGrammar), not a field literally named
        // "shipment.invoice.status". Three correct dsl-conformance-max queries said otherwise.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.groupbyjoin",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Invoice", "ui": { "label": "Invoice" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] },
                    { "name": "Shipment", "ui": { "label": "Shipment" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "invoice", "type": "reference",
                        "reference": { "target": "Invoice", "via": "id" } }
                    ] },
                    { "name": "DeliveryAttempt", "ui": { "label": "Delivery attempt" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "shipment", "type": "reference",
                        "reference": { "target": "Shipment", "via": "id" } }
                    ] }
                  ],
                  "queries": [
                    {
                      "name": "AttemptsByInvoiceStatus",
                      "concept": "DeliveryAttempt",
                      "groupBy": ["shipment.invoice.status"],
                      "aggregates": [ { "name": "attempts", "fn": "count" } ]
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
        // Each hop is a real reference to a real field, and --cascade has to be able to rewrite it.
        assertTrue(index.usagesOf("Shipment.invoice").stream()
                        .anyMatch(edge -> edge.site().equals(ReferenceIndex.SITE_QUERY_GROUP_BY_JOIN)),
                "the intermediate hop must be indexed, not just the final field");
        assertTrue(index.usagesOf("Invoice.status").stream()
                        .anyMatch(edge -> edge.site().equals(ReferenceIndex.SITE_QUERY_GROUP_BY)));
    }

    @Test
    void anActionInputNamingAProcedureVarIsResolvedNotOrphaned() throws Exception {
        // RenameOrderStatusProcedure declares NO parameters and reads $newStatus. The workbench
        // action's inputFields ["newStatus"] is correct, and that $var read is its only declaration.
        ReferenceIndex index = ReferenceIndex.build(parse("""
                {
                  "namespace": "xref.actioninput",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Order", "ui": { "label": "Order" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "status", "type": "string", "ui": { "label": "Status" } }
                    ] }
                  ],
                  "procedures": [
                    { "name": "RenameOrderStatus", "steps": [
                      { "name": "map-status", "type": "mapValue", "value": "$newStatus", "target": "status" },
                      { "name": "done", "type": "return", "value": "$status" }
                    ] }
                  ],
                  "autoPanels": [
                    {
                      "name": "OrderWorkbench",
                      "concept": "Order",
                      "route": "/order-wb",
                      "surfaces": ["transaction"],
                      "transaction": {
                        "fields": ["status"],
                        "actions": [
                          { "procedure": "RenameOrderStatus", "label": "Apply status",
                            "inputFields": ["newStatus"] }
                        ]
                      }
                    }
                  ]
                }
                """));

        assertEquals(List.of(), unresolvedOf(index), describe(unresolvedOf(index)));
        assertTrue(index.edges().stream()
                        .anyMatch(edge -> edge.toName().equals("RenameOrderStatus.newStatus")
                                && edge.resolution() == Resolution.RESOLVED),
                "resolved against the procedure's own $var read");
    }

    @Test
    void edgeOrderIsStableAcrossBuilds() throws Exception {
        // check-deterministic-generation.ps1 SHA-256s every emitted file across two generator runs.
        // A HashMap iteration order in the walker fails that gate, not this test -- so it is caught
        // here instead, where the failure names the cause.
        String json = """
                {
                  "namespace": "xref.determinism",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Alpha", "ui": { "label": "Alpha" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "one", "type": "string", "ui": { "label": "One" } },
                      { "name": "two", "type": "string", "ui": { "label": "Two" } }
                    ] },
                    { "name": "Beta", "ui": { "label": "Beta" }, "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "alpha", "type": "reference", "reference": { "target": "Alpha", "via": "id" } }
                    ] }
                  ],
                  "queries": [
                    { "name": "Q1", "concept": "Alpha", "orderBy": ["one", "two"] },
                    { "name": "Q2", "concept": "Beta" }
                  ]
                }
                """;

        List<String> first = ReferenceIndex.build(parse(json)).edges().stream()
                .map(ReferenceEdge::path).collect(Collectors.toList());
        List<String> second = ReferenceIndex.build(parse(json)).edges().stream()
                .map(ReferenceEdge::path).collect(Collectors.toList());

        assertEquals(first, second);
        assertNotEquals(0, first.size(), "the fixture must actually produce edges");
    }
}
