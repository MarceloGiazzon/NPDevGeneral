package com.finalexec.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalexec.live.LiveChangeHub;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FORM P5-T5: the HTTP layer of GET /api/live -- standalone MockMvc with the concept gateway and the context service
 * mocked. What is asserted is this controller's own wiring: the permission filter (the gateway's list gate), the status
 * codes of the refusals, the SSE framing of an event, and that closing the response frees the user's stream slot. The
 * hub, the caps and the transaction semantics have their own tests in runtimehost-core.
 */
class LiveControllerTest {

    private LiveChangeHub hub;
    private ConceptGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        hub = new LiveChangeHub(2, 16, 0, Runnable::run);
        gateway = Mockito.mock(ConceptGateway.class);
        when(gateway.query(any(ConceptQueryRequest.class), any())).thenAnswer(call -> {
            String concept = call.<ConceptQueryRequest>getArgument(0).conceptName();
            if (concept.equals("Secret")) {
                throw new ConceptGatewayAccessDeniedException("PERMISSION_DENIED", "no");
            }
            if (concept.equals("Ghost")) {
                throw new IllegalArgumentException("Unknown concept: Ghost");
            }
            return new ConceptPage(List.of(), 0, false);
        });
        RuntimeContextService context = Mockito.mock(RuntimeContextService.class);
        when(context.currentContext(any())).thenReturn(ExecutionContext.of("default", "alice"));
        mvc = MockMvcBuilders.standaloneSetup(new LiveController(hub, context, gateway)).build();
    }

    @Test
    void streamsOnlyConceptsTheCallerMayList_andFramesEventsAsSse() throws Exception {
        MvcResult started = mvc.perform(get("/api/live").param("concepts", "Ride,Secret,Ghost,RideOffer"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertEquals(1, hub.subscriberCount());
        assertEquals("no-cache, no-transform", started.getResponse().getHeader("Cache-Control"));

        hub.publish("default", "Ride", "r-1", 3L);
        hub.publish("default", "Secret", "s-1", 1L); // dropped at subscribe time: never delivered
        hub.publish("default", "Ghost", "g-1", 1L);
        hub.publish("other", "Ride", "r-2", 1L); // another tenant
        hub.publish("default", "RideOffer", "o-1", null);

        String body = started.getResponse().getContentAsString();
        assertTrue(body.contains("event:changed\ndata:{\"concept\":\"Ride\",\"id\":\"r-1\",\"version\":3}\n"), body);
        assertTrue(body.contains("\"concept\":\"RideOffer\",\"id\":\"o-1\",\"version\":null"), body);
        assertFalse(body.contains("Secret"), body);
        assertFalse(body.contains("Ghost"), body);
        assertFalse(body.contains("r-2"), body);
        assertTrue(body.startsWith(":connected"), "the stream opens with a comment so the client knows it is live: " + body);
    }

    @Test
    void refusals() throws Exception {
        mvc.perform(get("/api/live")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/live").param("concepts", "Ride;DROP")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/live").param("concepts", "A,B,C,D,E,F,G,H,I,J,K")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/live").param("concepts", "Secret,Ghost")).andExpect(status().isForbidden());
        assertEquals(0, hub.subscriberCount(), "a refused request leaves nothing open");
    }

    @Test
    void theStreamOverTheUserCapIs429_andACompletedStreamFreesItsSlot() throws Exception {
        MvcResult first = mvc.perform(get("/api/live").param("concepts", "Ride")).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(get("/api/live").param("concepts", "Ride")).andExpect(request().asyncStarted());
        mvc.perform(get("/api/live").param("concepts", "Ride")).andExpect(status().isTooManyRequests());
        assertEquals(2, hub.subscriberCount());
        // the container reports the peer gone: the emitter's completion callback closes the subscription
        first.getRequest().getAsyncContext().complete();
        assertEquals(1, hub.subscriberCount());
        mvc.perform(get("/api/live").param("concepts", "Ride")).andExpect(request().asyncStarted());
    }
}
