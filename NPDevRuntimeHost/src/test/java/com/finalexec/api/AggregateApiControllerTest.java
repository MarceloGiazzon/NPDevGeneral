package com.finalexec.api;

import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Session 1 remediation (NPDEV_MEGA_ROADMAP.md, 2026-09-14): a bare
 * {@code ResponseStatusException}'s reason never reached the client under
 * {@code server.error.include-message=never} -- confirmed live via curl, whose body had no
 * "message" key at all. Every {@code errorBody(...)} branch below is the fix: each must produce a
 * body with the business-language "message" the underlying exception carried, not just a status code.
 */
class AggregateApiControllerTest {

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final AggregateRuntime aggregateRuntime = Mockito.mock(AggregateRuntime.class);

    private MockMvc mockMvc() {
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("acme", "tester"));
        return MockMvcBuilders.standaloneSetup(new AggregateApiController(runtimeContextService, aggregateRuntime)).build();
    }

    @Test
    void load_returnsOkWithBody() throws Exception {
        when(aggregateRuntime.load(eq("StockTransfer"), eq("r1"), any())).thenReturn(Map.of("id", "r1"));
        mockMvc().perform(get("/api/runtime/aggregate/StockTransfer/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("r1"));
    }

    @Test
    void load_illegalArgument_returnsNotFoundWithMessage() throws Exception {
        when(aggregateRuntime.load(anyString(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("no such root"));
        mockMvc().perform(get("/api/runtime/aggregate/StockTransfer/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("no such root"));
    }

    @Test
    void load_illegalState_returnsServiceUnavailableWithMessage() throws Exception {
        when(aggregateRuntime.load(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("runtime not ready"));
        mockMvc().perform(get("/api/runtime/aggregate/StockTransfer/r1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("runtime not ready"));
    }

    @Test
    void commit_illegalArgument_returnsBadRequestWithMessage() throws Exception {
        when(aggregateRuntime.commit(anyString(), anyMap(), any()))
                .thenThrow(new IllegalArgumentException("balance invariant failed"));
        mockMvc().perform(post("/api/runtime/aggregate/StockTransfer")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("balance invariant failed"));
    }

    @Test
    void invoke_illegalArgument_returnsBadRequestWithMessage() throws Exception {
        when(aggregateRuntime.invoke(anyString(), anyString(), anyMap(), any()))
                .thenThrow(new IllegalArgumentException("unknown procedure"));
        mockMvc().perform(post("/api/runtime/aggregate/StockTransfer/invoke/Recompute")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("unknown procedure"));
    }

    @Test
    void checkBalances_ok_splitsCommaSeparatedNames() throws Exception {
        when(aggregateRuntime.checkBalances(eq("StockTransfer"), eq(List.of("PositionWithinAvailable", "Other")), anyMap()))
                .thenReturn(Map.of("__balances", Map.of()));
        mockMvc().perform(post("/api/runtime/aggregate/StockTransfer/check-balances/PositionWithinAvailable, Other")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void checkBalances_illegalState_returnsServiceUnavailableWithMessage() throws Exception {
        when(aggregateRuntime.checkBalances(anyString(), anyList(), anyMap()))
                .thenThrow(new IllegalStateException("balance rule not declared"));
        mockMvc().perform(post("/api/runtime/aggregate/StockTransfer/check-balances/PositionWithinAvailable")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("balance rule not declared"));
    }

    @Test
    void load_withNoAggregateRuntimeConfigured_returnsServiceUnavailable() throws Exception {
        when(runtimeContextService.currentContext(any())).thenReturn(ExecutionContext.of("acme", "tester"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AggregateApiController(runtimeContextService)).build();
        mockMvc.perform(get("/api/runtime/aggregate/StockTransfer/r1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Aggregate runtime is not configured."));
    }
}
