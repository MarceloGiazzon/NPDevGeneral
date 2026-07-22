package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelDataSource;
import com.npdev.dsl.v1.compiled.CompiledPanelLayout;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConceptGatewayTraceSink;
import com.npdev.kernel.concepts.ConceptListRequest;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.DefaultConceptGateway;
import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** LIFT-ROWOPS-P3: PanelRuntime.createRow/deleteRow against a real ConceptGateway. */
class PanelRuntimeRowOpsTest {
    private final RuntimeMetadataService metadataService = new RuntimeMetadataService(new ObjectMapper());

    private PanelRuntime runtimeFor(CompiledModel model, DefaultConceptGateway gateway) {
        return new PanelRuntime(
                metadataService,
                new PermissionAwareUiMetadataService(
                        metadataService,
                        new ObjectMapper(),
                        PermissionEvaluator.allowAll(),
                        new com.finalexec.npdev.service.BetaSecurityRoleEvaluator()
                ),
                model,
                gateway,
                null,
                null
        );
    }

    private static DefaultConceptGateway newGateway() {
        return new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                ConceptGatewaySemanticPolicy.noop(),
                ConceptGatewayTraceSink.noop()
        );
    }

    private static CompiledModel rowOpsPanelModel() {
        CompiledPanel panel = new CompiledPanel(
                "OrderPanel",
                "/orders",
                "Orders",
                List.of(
                        new CompiledPanelDataSource("orders", "Order", null, null, Map.of(), null, null, null,
                                List.of("add", "delete"), List.of("sku")),
                        new CompiledPanelDataSource("items", "OrderItem", null, null, Map.of(),
                                "orders", "id", "orderId", List.of("add", "delete"), List.of())
                ),
                new CompiledPanelLayout("table", List.of(), List.of("sku"), Map.of()),
                List.of(),
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                null
        );
        return new CompiledModel(
                "panel.runtime.rowops",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(panel)
        );
    }

    @Test
    void createRowOnRootDataSourceSavesThroughConceptGateway() {
        DefaultConceptGateway gateway = newGateway();
        PanelRuntime runtime = runtimeFor(rowOpsPanelModel(), gateway);

        Map<String, Object> result = runtime.createRow(
                "OrderPanel", "orders", Map.of("data", Map.of("sku", "ABC-1")),
                ExecutionContext.of("dev", "operator")
        );

        assertEquals("OK", result.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> record = (Map<String, Object>) result.get("result");
        assertNotNull(record.get("id"));

        List<ConceptRecord> rows = gateway.list(new ConceptListRequest("Order", null), ExecutionContext.of("dev", "operator"));
        assertEquals(1, rows.size());
    }

    @Test
    void createRowOnChildDataSourceInjectsParentFk() {
        DefaultConceptGateway gateway = newGateway();
        PanelRuntime runtime = runtimeFor(rowOpsPanelModel(), gateway);

        Map<String, Object> parent = runtime.createRow(
                "OrderPanel", "orders", Map.of("data", Map.of("sku", "ABC-1")),
                ExecutionContext.of("dev", "operator")
        );
        @SuppressWarnings("unchecked")
        String parentId = String.valueOf(((Map<String, Object>) parent.get("result")).get("id"));

        runtime.createRow(
                "OrderPanel", "items",
                Map.of("parentId", parentId, "data", Map.of("qty", 3)),
                ExecutionContext.of("dev", "operator")
        );

        List<ConceptRecord> items = gateway.list(new ConceptListRequest("OrderItem", null), ExecutionContext.of("dev", "operator"));
        assertEquals(1, items.size());
        assertEquals(parentId, String.valueOf(items.get(0).data().get("orderId")));
    }

    @Test
    void createRowOnChildDataSourceWithoutParentIdIsRejected() {
        PanelRuntime runtime = runtimeFor(rowOpsPanelModel(), newGateway());

        assertThrows(IllegalArgumentException.class, () -> runtime.createRow(
                "OrderPanel", "items", Map.of("data", Map.of("qty", 3)),
                ExecutionContext.of("dev", "operator")
        ));
    }

    @Test
    void createRowOnDataSourceWithoutAddRowOpIsRejected() {
        CompiledPanel panel = new CompiledPanel(
                "ReadOnlyPanel", "/readonly", "Read Only",
                List.of(new CompiledPanelDataSource("rows", "Order", null, null, Map.of(), null, null, null)),
                new CompiledPanelLayout("table", List.of(), List.of(), Map.of()),
                List.of(), null, null, List.of(), Map.of(), Map.of(), null
        );
        CompiledModel model = new CompiledModel(
                "panel.runtime.readonly", "1.0.0", "1.0.0", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(panel)
        );
        PanelRuntime runtime = runtimeFor(model, newGateway());

        assertThrows(IllegalArgumentException.class, () -> runtime.createRow(
                "ReadOnlyPanel", "rows", Map.of("data", Map.of("sku", "X")),
                ExecutionContext.of("dev", "operator")
        ));
    }

    @Test
    void deleteRowRemovesTheRecord() {
        DefaultConceptGateway gateway = newGateway();
        PanelRuntime runtime = runtimeFor(rowOpsPanelModel(), gateway);
        ExecutionContext ctx = ExecutionContext.of("dev", "operator");

        Map<String, Object> created = runtime.createRow("OrderPanel", "orders", Map.of("data", Map.of("sku", "ABC-1")), ctx);
        @SuppressWarnings("unchecked")
        String id = String.valueOf(((Map<String, Object>) created.get("result")).get("id"));

        Map<String, Object> deleteResult = runtime.deleteRow("OrderPanel", "orders", id, ctx);
        assertEquals("OK", deleteResult.get("status"));

        assertTrue(gateway.list(new ConceptListRequest("Order", null), ctx).isEmpty());
    }

    @Test
    void crossTenantDeleteIsBlocked() {
        DefaultConceptGateway gateway = newGateway();
        PanelRuntime runtime = runtimeFor(rowOpsPanelModel(), gateway);

        Map<String, Object> created = runtime.createRow(
                "OrderPanel", "orders", Map.of("data", Map.of("sku", "ABC-1")),
                ExecutionContext.of("tenant-a", "operator")
        );
        @SuppressWarnings("unchecked")
        String id = String.valueOf(((Map<String, Object>) created.get("result")).get("id"));

        assertThrows(RuntimeException.class, () -> runtime.deleteRow(
                "OrderPanel", "orders", id, ExecutionContext.of("tenant-b", "operator")
        ));

        assertEquals(1, gateway.list(
                new ConceptListRequest("Order", null), ExecutionContext.of("tenant-a", "operator")
        ).size());
    }
}
