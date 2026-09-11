package com.npdev.dsl.v1.resolution;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.EventAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3.2 (NPDEV_PATH_A_REALIGNMENT_PLAN.md Decision D2, {@code docs/architecture/DSL_SPECIALIZATION_POLICY.md}):
 * one conformance fixture per table row that {@link ModelResolverSpecializationTest} and the
 * {@code specialization/valid-specialization.json} golden fixture do not already cover. Every
 * method here proves exactly one row of that table -- see the doc for the full table and the
 * rationale behind each verdict.
 */
class InheritancePolicyConformanceTest {

    // -- concept -------------------------------------------------------------------------------

    @Test
    void conceptLifecycleSpecializationWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.lifecycle", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Order", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "status", "type": "string" } ],
                      "lifecycle": { "statusField": "status", "transitions": [ { "from": "New", "to": "Shipped" } ] } },
                    { "name": "PriorityOrder", "specializes": "Order",
                      "fields": [ { "name": "rush", "type": "boolean" } ],
                      "lifecycle": { "statusField": "status", "transitions": [ { "from": "New", "to": "Rushed" } ] } },
                    { "name": "PlainOrder", "specializes": "Order",
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("Rushed", concept(resolved, "PriorityOrder").getLifecycle().getTransitions().get(0).getTo(),
                "specialization's own lifecycle must win");
        assertEquals("Shipped", concept(resolved, "PlainOrder").getLifecycle().getTransitions().get(0).getTo(),
                "a specialization with no lifecycle block must inherit the base's whole lifecycle");
    }

    @Test
    void conceptAccessSpecializationWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.access", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Order", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "ownerId", "type": "string" } ],
                      "access": { "read": "ownerId == $user.id", "write": "ownerId == $user.id" } },
                    { "name": "AdminOrder", "specializes": "Order",
                      "fields": [ { "name": "tier", "type": "string" } ],
                      "access": { "read": "true", "write": "true" } },
                    { "name": "PlainOrder", "specializes": "Order",
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("true", concept(resolved, "AdminOrder").getAccess().getRead(),
                "specialization's own access rule must replace the base's, not merge with it");
        assertEquals("ownerId == $user.id", concept(resolved, "PlainOrder").getAccess().getRead(),
                "a specialization with no access block must inherit the base's whole rule");
    }

    @Test
    void conceptModuleSpecializationWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.module", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Invoice", "module": "billing", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "MedicalInvoice", "specializes": "Invoice", "module": "medical",
                      "fields": [ { "name": "doctorId", "type": "uuid" } ] },
                    { "name": "PlainInvoice", "specializes": "Invoice",
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("medical", concept(resolved, "MedicalInvoice").getModule());
        assertEquals("billing", concept(resolved, "PlainInvoice").getModule());
    }

    @Test
    void conceptIndexesAreAdditiveAcrossSpecialization() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.indexes", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Invoice", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "code", "type": "string" } ],
                      "indexes": [ { "name": "idx_code", "fields": [ "code" ] } ] },
                    { "name": "MedicalInvoice", "specializes": "Invoice",
                      "fields": [ { "name": "doctorId", "type": "uuid" } ],
                      "indexes": [ { "name": "idx_doctor", "fields": [ "doctorId" ] } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        List<String> indexNames = concept(resolved, "MedicalInvoice").getIndexes().stream()
                .map(com.npdev.dsl.v1.ast.IndexAst::getName)
                .toList();
        assertEquals(List.of("idx_code", "idx_doctor"), indexNames,
                "specialization indexes must be appended to the base's, never replace them");
    }

    @Test
    void conceptSoftDeleteIsStickyTrueAcrossSpecialization() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.softdelete", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Durable", "softDelete": true, "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "DurableChild", "specializes": "Durable",
                      "fields": [ { "name": "note", "type": "string" } ] },
                    { "name": "Volatile", "softDelete": false, "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "VolatileOptIn", "specializes": "Volatile", "softDelete": true,
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertTrue(concept(resolved, "DurableChild").isSoftDelete(),
                "a base's softDelete guarantee cannot be silently dropped by a specialization");
        assertTrue(concept(resolved, "VolatileOptIn").isSoftDelete(),
                "a specialization may still opt in on its own even when the base does not");
    }

    @Test
    void conceptTemporalIsStickyTrueAcrossSpecialization() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.temporal", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "PriceHistory", "temporal": true, "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "PriceHistoryChild", "specializes": "PriceHistory",
                      "fields": [ { "name": "note", "type": "string" } ] },
                    { "name": "CurrentPrice", "temporal": false, "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "CurrentPriceOptIn", "specializes": "CurrentPrice", "temporal": true,
                      "fields": [ { "name": "note", "type": "string" } ] }
                  ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertTrue(concept(resolved, "PriceHistoryChild").isTemporal(),
                "a base's temporal durability contract cannot be silently undone by a specialization");
        assertTrue(concept(resolved, "CurrentPriceOptIn").isTemporal(),
                "a specialization may still opt in on its own even when the base does not");
    }

    /**
     * Nested concept events are add-only, same shape as fields -- but a duplicate name across
     * inheritance never reaches {@code ModelResolver.mergeConcept}'s own dedup branch: every event
     * name (nested or top-level) is indexed into ONE global map before concept resolution even
     * starts ({@code ModelResolver.resolve}, {@code indexByName} over {@code source.getEvents()}),
     * so two concepts anywhere in the model sharing a literal event name are already rejected
     * upstream. That makes {@code mergeConcept}'s local dedup check unreachable through inheritance
     * alone -- this fixture proves the row's VERDICT (duplicate rejected), not which branch does it.
     */
    @Test
    void conceptNestedEventsRejectDuplicateNameAcrossInheritance() throws Exception {
        ModelAst source = parseJson("""
                {
                  "namespace": "policy.events", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "Base", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "events": [ { "name": "BaseCreated", "payload": [] } ] },
                    { "name": "Derived", "specializes": "Base",
                      "fields": [ { "name": "note", "type": "string" } ],
                      "events": [ { "name": "BaseCreated", "payload": [] } ] }
                  ]
                }
                """);
        ModelResolutionException exception = assertThrows(
                ModelResolutionException.class,
                () -> new ModelResolver().resolve(source)
        );
        assertEquals(ResolutionDiagnosticCode.CONFLICT_DUPLICATE_MEMBER, exception.getCode());
    }

    /**
     * PACK-2's "else base" branch: an app-native specialization (no pack of its own) of a
     * pack-contributed concept must keep the base's pack origin rather than losing it to null. The
     * "specialization wins" branch is a one-line ternary with no separate code path to lose, so it
     * is not independently fixture-covered -- every other "specialization wins else base" row above
     * already exercises that same ternary shape.
     */
    @Test
    void conceptOriginFallsBackToBaseWhenSpecializationIsPackless(@TempDir Path temp) throws Exception {
        write(temp, "packs/base/pack.json", """
                {
                  "dslVersion": "1.0.0", "pack": "base", "version": "1.0.0",
                  "namespace": "com.npdev.base",
                  "description": "P3.2 conformance fixture: origin fallback for a packless specialization.",
                  "concepts": [
                    { "name": "Widget", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string" } ] }
                  ]
                }
                """);
        Path model = write(temp, "model.json", """
                {
                  "namespace": "policy.origin", "dslVersion": "1.0.0", "version": "1.0",
                  "packs": [ { "$ref": "packs/base/pack.json" } ],
                  "concepts": [
                    { "name": "CustomWidget", "specializes": "base::Widget",
                      "fields": [ { "name": "sku", "type": "string" } ] }
                  ]
                }
                """);

        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        ModelAst ast = new JsonModelParser().parse(resolvedSource);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        ConceptAst customWidget = resolved.modelAst().getConcepts().stream()
                .filter(c -> c.getName().endsWith("CustomWidget"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CustomWidget not found among: "
                        + resolved.modelAst().getConcepts().stream().map(ConceptAst::getName).toList()));

        assertNotNull(customWidget.getOrigin(),
                "a packless specialization of a pack concept must not silently lose the base's origin");
        assertEquals("base", customWidget.getOrigin().packId());
    }

    // -- capability ------------------------------------------------------------------------------

    @Test
    void capabilityTypeCannotBeChangedBySpecialization() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.capability.type", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Dummy", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "capabilities": [
                    { "name": "persistence", "type": "PersistenceCapability", "operations": [] },
                    { "name": "notif", "specializes": "persistence", "type": "NotificationCapability", "operations": [] }
                  ]
                }
                """);
    }

    @Test
    void capabilityOperationsAreAddOnlyAndRejectOverride() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.capability.ops", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Dummy", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "capabilities": [
                    { "name": "persistence", "type": "PersistenceCapability", "operations": [ %s ] },
                    { "name": "medicalPersistence", "specializes": "persistence", "operations": [ %s ] }
                  ]
                }
                """.formatted(operation("save"), operation("save")));
    }

    // -- event -------------------------------------------------------------------------------------

    /**
     * Only reachable when two DIFFERENT concepts each nest an event and one specializes the
     * other's -- same-concept specialization trivially matches its own concept name.
     */
    @Test
    void eventConceptCannotBeChangedBySpecialization() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.event.concept", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [
                    { "name": "A", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "events": [ { "name": "AEvent", "payload": [] } ] },
                    { "name": "B", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ],
                      "events": [ { "name": "BEvent", "specializes": "AEvent", "payload": [] } ] }
                  ]
                }
                """);
    }

    /**
     * {@code mode}/triggerMode is only accepted on a concept-nested event (the parser rejects it on
     * a top-level one). All three events below nest under the SAME concept so their {@code
     * conceptName} always matches and the unrelated {@code eventConceptCannotBeChangedBySpecialization}
     * guard never fires here.
     */
    @Test
    void eventTriggerModeSpecializationWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.event.mode", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Dummy", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ],
                    "events": [
                      { "name": "Ping", "version": "1.0", "mode": "create", "payload": [ { "name": "x", "type": "string" } ] },
                      { "name": "PingUpdate", "specializes": "Ping", "mode": "update" },
                      { "name": "PingSame", "specializes": "Ping" }
                    ] } ]
                }
                """);
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("update", event(resolved, "PingUpdate").getTriggerMode());
        assertEquals("create", event(resolved, "PingSame").getTriggerMode());
    }

    // -- flow --------------------------------------------------------------------------------------

    @Test
    void flowCannotRedefineStepsWhenSpecializing() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.flow.steps", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "BaseFlow", "concept": "Order", "steps": [ %s ] },
                    { "name": "BadSteps", "specializes": "BaseFlow", "steps": [ %s ] }
                  ]
                }
                """.formatted(returnStep("s1"), returnStep("s2")));
    }

    @Test
    void flowHooksWithoutSpecializesIsRejected() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.flow.hooks", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "BadHooks", "concept": "Order",
                      "steps": [ %s ],
                      "hooks": [ { "position": "before", "targetStep": "x", "steps": [ %s ] } ] }
                  ]
                }
                """.formatted(returnStep("s1"), returnStep("h1")));
    }

    /**
     * Proves {@code concept} and {@code schemas}. {@code mode} shares the exact same
     * ILLEGAL_OVERRIDE guard shape in {@code mergeFlow} but is not independently reachable: the
     * parser only ever sets {@code FlowAst.mode} from {@code flow.input.mode}, and {@code
     * flowInput} requires {@code concept} on that same object whenever it is used at all -- so any
     * model that redeclares {@code mode} on a specialization also redeclares {@code concept}, which
     * throws first (same diagnostic code, different message). Not a gap: the two guards are
     * redundant by construction, not silently missing one of them.
     */
    @Test
    void flowCannotOverrideConceptModeOrSchemas() throws Exception {
        assertIllegalOverride("""
                {
                  "namespace": "policy.flow.concept", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "BaseFlow", "concept": "Order", "steps": [ %s ] },
                    { "name": "BadConcept", "specializes": "BaseFlow", "concept": "Order", "hooks": [] }
                  ]
                }
                """.formatted(returnStep("s1")));

        assertIllegalOverride("""
                {
                  "namespace": "policy.flow.schema", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "BaseFlow", "concept": "Order", "steps": [ %s ] },
                    { "name": "BadSchema", "specializes": "BaseFlow", "inputSchema": { "type": "object", "properties": {} }, "hooks": [] }
                  ]
                }
                """.formatted(returnStep("s1")));
    }

    @Test
    void flowScheduleSpecializationWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.flow.schedule", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "BaseFlow", "concept": "Order", "schedule": { "cron": "0 0 * * *" }, "steps": [ %s ] },
                    { "name": "NightlySpecial", "specializes": "BaseFlow", "schedule": { "cron": "0 3 * * *" }, "hooks": [] },
                    { "name": "NightlyDefault", "specializes": "BaseFlow", "hooks": [] }
                  ]
                }
                """.formatted(returnStep("s1")));
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("0 3 * * *", flow(resolved, "NightlySpecial").getSchedule().getCron());
        assertEquals("0 0 * * *", flow(resolved, "NightlyDefault").getSchedule().getCron());
    }

    @Test
    void flowStartEndpointIsStickyTrueAcrossSpecialization() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.flow.entry", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "EntryFlow", "concept": "Order", "startEndpoint": true, "steps": [ %s ] },
                    { "name": "EntryChild", "specializes": "EntryFlow", "hooks": [] },
                    { "name": "HiddenFlow", "concept": "Order", "startEndpoint": false, "steps": [ %s ] },
                    { "name": "HiddenChildOptIn", "specializes": "HiddenFlow", "startEndpoint": true, "hooks": [] }
                  ]
                }
                """.formatted(returnStep("s1"), returnStep("s2")));
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertTrue(flow(resolved, "EntryChild").isStartEndpoint(),
                "a base flow exposed as an entry point cannot be silently hidden by a specialization");
        assertTrue(flow(resolved, "HiddenChildOptIn").isStartEndpoint(),
                "a specialization may still opt in on its own even when the base does not");
    }

    @Test
    void flowActionFirstNonNullWinsElseBase() throws Exception {
        ModelAst ast = parseJson("""
                {
                  "namespace": "policy.flow.action", "dslVersion": "1.0.0", "version": "v1",
                  "concepts": [ { "name": "Order", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
                  "flows": [
                    { "name": "ActionBase", "concept": "Order", "action": { "label": "Base Action" }, "steps": [ %s ] },
                    { "name": "ActionChildNoOverride", "specializes": "ActionBase", "hooks": [] },
                    { "name": "PlainBase", "concept": "Order", "steps": [ %s ] },
                    { "name": "PlainChildWithAction", "specializes": "PlainBase", "action": { "label": "Child Action" }, "hooks": [] }
                  ]
                }
                """.formatted(returnStep("s1"), returnStep("s2")));
        ResolvedModel resolved = new ModelResolver().resolve(ast);

        assertEquals("Base Action", flow(resolved, "ActionChildNoOverride").getAction().getLabel());
        assertEquals("Child Action", flow(resolved, "PlainChildWithAction").getAction().getLabel());
    }

    // -- fixtures --------------------------------------------------------------------------------

    private static String returnStep(String name) {
        return "{ \"name\": \"" + name + "\", \"type\": \"return\", \"value\": \"$input\" }";
    }

    private static String operation(String name) {
        return "{ \"name\": \"" + name + "\", "
                + "\"input\": { \"type\": \"object\", \"properties\": {} }, "
                + "\"output\": { \"type\": \"object\", \"properties\": {} } }";
    }

    private static ConceptAst concept(ResolvedModel resolved, String name) {
        return resolved.modelAst().getConcepts().stream()
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Concept not found: " + name));
    }

    private static EventAst event(ResolvedModel resolved, String name) {
        return resolved.modelAst().getEvents().stream()
                .filter(e -> name.equals(e.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Event not found: " + name));
    }

    private static FlowAst flow(ResolvedModel resolved, String name) {
        return resolved.modelAst().getFlows().stream()
                .filter(f -> name.equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Flow not found: " + name));
    }

    private static void assertIllegalOverride(String json) throws Exception {
        ModelAst source = parseJson(json);
        ModelResolutionException exception = assertThrows(
                ModelResolutionException.class,
                () -> new ModelResolver().resolve(source)
        );
        assertEquals(ResolutionDiagnosticCode.ILLEGAL_OVERRIDE, exception.getCode());
    }

    private static ModelAst parseJson(String json) throws Exception {
        Path temp = Files.createTempFile("npdev-inheritance-policy-", ".json");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        return new JsonModelParser().parse(temp);
    }

    private static Path write(Path root, String relative, String content) throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
        return path;
    }
}
