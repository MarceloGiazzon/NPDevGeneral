package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.controllers.GeneratedQueryController;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptAggregateRequest;
import com.npdev.kernel.concepts.ConceptAggregateResult;
import com.npdev.kernel.concepts.ConceptGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Friction #5: the REAL generated {@code GeneratedQueryController} of whichever sample app this test
 * is assembled into runs a query's {@code lookups[]} through the gateway, adds its {@code computed[]}
 * column, and applies {@code having}/{@code orderBy} only afterwards -- standalone MockMvc, gateway
 * mocked, so the assertions are about the controller's own wiring, not a store.
 */
class GeneratedQueryControllerLookupTest {

    private static final String MODEL = """
            { "namespace": "friction5.lookup", "dslVersion": "1.0.0", "version": "1.0",
              "concepts": [
                { "name": "Cell", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "capId", "type": "string" } ] },
                { "name": "Owned", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "ownerUsername", "type": "string" },
                  { "name": "capId", "type": "string" },
                  { "name": "quantity", "type": "integer" } ] }
              ],
              "queries": [
                { "name": "ToDrink", "concept": "Cell", "groupBy": [ "capId" ],
                  "aggregates": [ { "name": "needed", "fn": "count" } ],
                  "lookups": [ { "name": "owned", "concept": "Owned", "fn": "sum", "field": "quantity",
                                 "on": { "capId": "capId" }, "where": "ownerUsername == $user.id" } ],
                  "computed": [ { "name": "toDrink", "expression": "needed - owned" } ],
                  "having": "toDrink > 0", "orderBy": [ "toDrink desc" ] }
              ] }
            """;

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @Test
    void lookupAndComputedColumnsReachTheResponseAndHavingSeesThem() throws Exception {
        Path modelPath = Files.createTempFile("npdev-query-lookup-", ".json");
        Files.writeString(modelPath, MODEL);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));

        List<ConceptAggregateRequest> requests = new ArrayList<>();
        ConceptGateway gateway = Mockito.mock(ConceptGateway.class);
        when(gateway.aggregate(any(), any())).thenAnswer(invocation -> {
            ConceptAggregateRequest request = invocation.getArgument(0);
            requests.add(request);
            if ("Owned".equals(request.conceptName())) {
                return new ConceptAggregateResult(List.of(row("capId", "red", "owned", 4L), row("capId", "blue", "owned", 9L)));
            }
            return new ConceptAggregateResult(List.of(
                    row("capId", "red", "needed", 10L),
                    row("capId", "blue", "needed", 3L),
                    row("capId", "green", "needed", 2L)));
        });
        RuntimeContextService contexts = Mockito.mock(RuntimeContextService.class);
        when(contexts.currentContext(any())).thenReturn(ExecutionContext.of("tenant-a", "tito"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new GeneratedQueryController(new ModelHolder(model), gateway, contexts)).build();
        mvc.perform(get("/api/queries/ToDrink"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[0].capId").value("red"))
                .andExpect(jsonPath("$.rows[0].owned").value(4))
                .andExpect(jsonPath("$.rows[0].toDrink").value(6))
                .andExpect(jsonPath("$.rows[1].capId").value("green"))
                .andExpect(jsonPath("$.rows[1].owned").value(0))
                .andExpect(jsonPath("$.rows[1].toDrink").value(2));

        assertEquals(2, requests.size());
        // The base aggregate goes to the store WITHOUT having/sort/limit -- they need the new columns.
        assertTrue(requests.get(0).query().having().isEmpty());
        assertTrue(requests.get(0).query().sorts().isEmpty());
        // The lookup's own where ($user.id) is compiled against the caller and sent with it.
        assertEquals("Owned", requests.get(1).conceptName());
        assertEquals("tito", String.valueOf(requests.get(1).query().filters().get(0).value()));
    }
}
