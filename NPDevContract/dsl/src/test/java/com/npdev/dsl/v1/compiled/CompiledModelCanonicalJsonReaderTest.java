package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompiledModelCanonicalJsonReaderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void roundTripsLifecycleAndFlowMapRefs() throws Exception {
        CompiledSchema emailSchema = new CompiledSchema(
                "string",
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                "coalesce(primaryEmail, 'noreply@example.com')",
                null,
                "Email",
                3,
                100,
                null,
                null,
                null
        );

        CompiledSchema chartLabelSchema = new CompiledSchema(
                "string",
                Map.of(),
                null,
                List.of(),
                List.of(),
                null,
                null,
                "concat(lastName, ', ', firstName)",
                "Chart label",
                null,
                null,
                null,
                null,
                null
        );

        CompiledSchema inputSchema = new CompiledSchema(
                "object",
                Map.of("email", emailSchema),
                null,
                List.of("email"),
                List.of(),
                null,
                "Input",
                null,
                null,
                null,
                null,
                null
        );

        CompiledCapabilityExecutionPolicy executionPolicy = new CompiledCapabilityExecutionPolicy(
                2,
                50L,
                1000L,
                3,
                2000L,
                4,
                "requestId",
                "transient"
        );

        CompiledCapabilityCall capabilityCall = new CompiledCapabilityCall(
                "notification",
                "NotificationCapability",
                "send",
                List.of("input.email"),
                "input",
                "result",
                inputSchema,
                null,
                executionPolicy
        );

        CompiledActionMetadata flowAction = new CompiledActionMetadata(
                "Create user",
                "Create this user?",
                "User created.",
                "Review the user payload.",
                "low",
                null,
                "users.create",
                "user-create"
        );

        CompiledActionMetadata flowStepAction = new CompiledActionMetadata(
                "Send welcome notification",
                null,
                "Notification sent.",
                "Check notification bindings.",
                "low",
                null,
                "notifications.send",
                "welcome-notification"
        );

        CompiledFlowStep flowStep = new CompiledFlowStep(
                "map-and-send",
                "callCapability",
                "before-send",
                "User",
                List.of("UserEmailRequired"),
                "UserCreated",
                "payload",
                Map.of("email", "input.email"),
                "input != null",
                List.of(),
                List.of(),
                null,
                null,
                null,
                Map.of(),
                300L,
                "input",
                "payload",
                "result",
                capabilityCall,
                flowStepAction
        );

        CompiledLifecycle lifecycle = new CompiledLifecycle(
                "status",
                List.of(new CompiledStateTransition(
                        "Draft",
                        "Active",
                        List.of("activatedAt"),
                        "UserActivated",
                        null,
                        "Activate user",
                        Map.of(),
                        new CompiledActionMetadata(
                                "Activate user",
                                "Activate this user?",
                                "User activated.",
                                "Only draft users can be activated.",
                                "medium",
                                "status == 'Draft'",
                                "users.activate",
                                "user-activate"
                        )
                ))
        );

        CompiledConcept entity = new CompiledConcept(
                "User",
                "User",
                "users",
                List.of(
                        new CompiledField("id", "uuid", "String", true, true, true),
                        new CompiledField(
                                "managerId",
                                "reference",
                                "java.util.UUID",
                                false,
                                false,
                                false,
                                List.of(),
                                "User",
                                new CompiledReferenceSemantics(
                                        "User",
                                        false,
                                        "email",
                                        List.of("email"),
                                        List.of("email", "status"),
                                        "deny",
                                        "{{email}}",
                                        List.of("email", "status"),
                                        "{{email}} | {{status}}",
                                        "active-users"
                                ),
                                null,
                                null,
                                List.of()
                        ),
                        new CompiledField(
                                "chartLabel",
                                "string",
                                "String",
                                false,
                                false,
                                false,
                                List.of(),
                                null,
                                null,
                                null,
                                chartLabelSchema,
                                List.of()
                        ),
                        new CompiledField(
                                "status",
                                "string",
                                "String",
                                false,
                                true,
                                false,
                                List.of("Draft", "Active"),
                                null,
                                null,
                                null,
                                null,
                                List.of(
                                        new CompiledEnumOption("Draft", "Draft", 10, "Open", true, false, null, "info", "Initial state"),
                                        new CompiledEnumOption("Active", "Active", 20, "Open", false, false, null, "success", "Enabled state")
                                )
                        )
                ),
                List.of("email != null"),
                List.of(new CompiledInvariant("UserEmailRequired", "expression", null, "email != null")),
                lifecycle,
                null,
                null,
                null,
                List.of(),
                new CompiledConceptAccess("ownerId == $user.id", "ownerId == $user.id")
        );

        CompiledCapability capability = new CompiledCapability(
                "notification",
                "NotificationCapability",
                List.of(new CompiledCapabilityOperation(
                        "send",
                        List.of("payload"),
                        List.of("result"),
                        inputSchema,
                        null,
                        executionPolicy
                ))
        );

        CompiledEvent event = new CompiledEvent(
                "UserCreated",
                "User",
                List.of(new CompiledEventField("userId", "string"))
        );

        CompiledFlow flow = new CompiledFlow(
                "CreateUserFlow",
                "User",
                "sync",
                List.of(flowStep),
                inputSchema,
                null,
                flowAction,
                false,
                new CompiledFlowSchedule("0 0 2 * * *", List.of("acme", "beta"))
        );

        CompiledOrchestration orchestration = new CompiledOrchestration(
                "NotifyUser",
                "status == 'Active'",
                new CompiledOrchestrationTrigger("event", "UserCreated"),
                List.of(new CompiledOrchestrationAction(
                        "callCapability",
                        "User",
                        "notification",
                        "send",
                        null,
                        null,
                        Map.of("email", "input.email"),
                        new CompiledActionMetadata(
                                "Send welcome email",
                                null,
                                "Email queued.",
                                "Retry after notification recovery.",
                                "low",
                                null,
                                "notifications.send",
                                "welcome-email"
                        )
                ))
        );

        Map<String, CompiledConcept> entities = new LinkedHashMap<>();
        entities.put(entity.getName(), entity);

        CompiledModel original = new CompiledModel(
                "demo",
                "1.0.0",
                "v1",
                entities,
                List.of(capability),
                List.of(new CompiledCapabilityBinding("NotificationCapability", "notification-inproc")),
                List.of(event),
                List.of(flow),
                List.of(orchestration)
        );

        String json = CompiledModelCanonicalJson.toJson(original);
        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.has("concepts"), "Canonical compiled model JSON must emit concepts.");
        assertFalse(root.has("entities"), "Canonical compiled model JSON must not emit the legacy entities key.");

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);

        CompiledConcept restoredEntity = restored.findConcept("User").orElseThrow();
        assertNotNull(restoredEntity.getLifecycle());
        assertEquals("status", restoredEntity.getLifecycle().getStatusField());
        assertEquals(1, restoredEntity.getLifecycle().getTransitions().size());
        assertEquals("UserActivated", restoredEntity.getLifecycle().getTransitions().get(0).getEvent());
        assertNotNull(restoredEntity.getLifecycle().getTransitions().get(0).getAction());
        assertEquals("Activate user", restoredEntity.getLifecycle().getTransitions().get(0).getAction().getLabel());

        CompiledFlow restoredFlow = restored.findFlow("CreateUserFlow").orElseThrow();
        assertNotNull(restoredFlow.getAction());
        assertEquals("Create user", restoredFlow.getAction().getLabel());
        assertEquals(1, restoredFlow.getSteps().size());
        assertEquals("input", restoredFlow.getSteps().get(0).getMapFromRef());
        assertEquals("payload", restoredFlow.getSteps().get(0).getMapToRef());
        assertNotNull(restoredFlow.getSteps().get(0).getCapabilityCall());
        assertEquals("notification", restoredFlow.getSteps().get(0).getCapabilityCall().getCapabilityName());
        // LNCH-12: schedule must survive the canonical JSON round trip that every generated app's
        // NPDevModelProvider actually reads at boot -- same class of gap LNCH-13's access field had.
        assertNotNull(restoredFlow.getSchedule(), "schedule must round-trip through canonical JSON");
        assertEquals("0 0 2 * * *", restoredFlow.getSchedule().getCron());
        assertEquals(List.of("acme", "beta"), restoredFlow.getSchedule().getTenantScope());
        assertNotNull(restoredFlow.getSteps().get(0).getAction());
        assertEquals("Send welcome notification", restoredFlow.getSteps().get(0).getAction().getLabel());
        CompiledField restoredManagerId = findField(restoredEntity, "managerId");
        CompiledField restoredChartLabel = findField(restoredEntity, "chartLabel");
        CompiledField restoredStatus = findField(restoredEntity, "status");
        assertNotNull(restoredManagerId.getReferenceSemantics());
        assertEquals("email", restoredManagerId.getReferenceSemantics().getDisplayField());
        assertEquals("deny", restoredManagerId.getReferenceSemantics().getInlineCreatePolicy());
        assertEquals("{{email}}", restoredManagerId.getReferenceSemantics().getDisplayTemplate());
        assertEquals(List.of("email", "status"), restoredManagerId.getReferenceSemantics().getPickerColumns());
        assertEquals("{{email}} | {{status}}", restoredManagerId.getReferenceSemantics().getPreviewCardTemplate());
        assertEquals("active-users", restoredManagerId.getReferenceSemantics().getDefaultFilter());
        assertEquals("coalesce(primaryEmail, 'noreply@example.com')",
                restoredFlow.getSteps().get(0).getCapabilityCall().getInputSchema().getProperties().get("email").getDefaultExpression());
        assertEquals("concat(lastName, ', ', firstName)",
                restoredChartLabel.getSchema().getDerivedExpression());
        assertEquals(2, restoredStatus.getEnumOptions().size());
        assertTrue(restoredStatus.getEnumOptions().get(0).isDefaultValue());
        assertEquals("Send welcome email", restored.getOrchestrationRules().get(0).getActions().get(0).getAction().getLabel());

        // LNCH-13: access (row-level authorization) must survive the canonical JSON round trip --
        // confirmed live that it didn't (the writer never emitted it, the reader never parsed it)
        // before this test/fix, exactly the same class of gap LNCH-6's indexes had.
        assertNotNull(restoredEntity.getAccess(), "access must round-trip through canonical JSON");
        assertEquals("ownerId == $user.id", restoredEntity.getAccess().getRead());
        assertEquals("ownerId == $user.id", restoredEntity.getAccess().getWrite());
    }

    @Test
    void readsLegacyEntitiesAlias() throws Exception {
        String json = """
                {
                  "dslVersion": "1.0.0",
                  "namespace": "demo",
                  "version": "v1",
                  "entities": [
                    {
                      "name": "User",
                      "className": "User",
                      "tableName": "users",
                      "fields": [
                        {
                          "name": "id",
                          "dslType": "uuid",
                          "javaType": "String",
                          "id": true,
                          "required": true,
                          "unique": true
                        }
                      ]
                    }
                  ]
                }
                """;

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);

        assertTrue(restored.findConcept("User").isPresent(), "Reader should preserve the legacy entities alias.");
    }

    private static CompiledField findField(CompiledConcept entity, String fieldName) {
        return entity.getFields()
                .stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow();
    }
}
