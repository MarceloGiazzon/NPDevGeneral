package com.finalexec.api;

import com.npdev.generated.runtime.dto.CorrelationTimelineResponse;
import com.npdev.generated.runtime.dto.FlowDefinitionResponse;
import com.npdev.generated.runtime.service.KernelFacade;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated runtime DTO mappers (RuntimeApiEmitter, emitted for every app regardless of model)
 * had no direct test: these pin the {@code from(...)} projections the runtime controllers return,
 * including the null-timeline fallback the correlation endpoint relies on for an unknown id.
 */
class RuntimeDtoMappingTest {

    @Test
    void correlationTimelineFromNullIsAnEmptyTimelineNotAnNpe() {
        CorrelationTimelineResponse response = CorrelationTimelineResponse.from(null);

        assertNull(response.correlationId());
        assertTrue(response.executions().isEmpty());
        assertTrue(response.events().isEmpty());
        assertTrue(response.traceExecutionIds().isEmpty());
    }

    @Test
    void correlationTimelineCarriesIdAndTraceIds() {
        KernelFacade.CorrelationTimeline timeline = new KernelFacade.CorrelationTimeline(
                "corr-1", List.of(), List.of(), List.of("exec-1", "exec-2"));

        CorrelationTimelineResponse response = CorrelationTimelineResponse.from(timeline);

        assertEquals("corr-1", response.correlationId());
        assertTrue(response.executions().isEmpty());
        assertTrue(response.events().isEmpty());
        assertEquals(List.of("exec-1", "exec-2"), response.traceExecutionIds());
    }

    @Test
    void flowDefinitionCopiesEveryViewField() {
        Map<String, Object> input = Map.of("type", "object");
        Map<String, Object> output = Map.of("type", "string");
        KernelFacade.FlowDefinitionView view = new KernelFacade.FlowDefinitionView(
                "SubmitThing", "Thing", "sync", input, output, List.of());

        FlowDefinitionResponse response = FlowDefinitionResponse.from(view);

        assertEquals("SubmitThing", response.name());
        assertEquals("Thing", response.concept());
        assertEquals("sync", response.mode());
        assertEquals(input, response.inputSchema());
        assertEquals(output, response.outputSchema());
        assertTrue(response.steps().isEmpty());
    }
}
