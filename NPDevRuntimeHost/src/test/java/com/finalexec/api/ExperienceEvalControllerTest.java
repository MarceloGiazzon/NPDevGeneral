package com.finalexec.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finalexec.experience.ExperienceCatalog;
import com.finalexec.experience.ExperienceEvaluator;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQueryRequest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FORM P5-T4: the HTTP layer over {@code ExperienceEvaluator} -- standalone MockMvc with the concept gateway and the
 * context service mocked, so the assertions are about this controller's own wiring: the request/answer shape, that only
 * text from the caller's own experience reaches the gateway, that it reaches it as the caller, the cap and that a
 * denial is a plain null. The decision tables are in runtimehost-core's {@code ExperienceEvaluatorTest}.
 */
class ExperienceEvalControllerTest {

    private static final String EXPERIENCES = """
            { "experiences": [ { "name": "warehouse", "for": ["WarehouseManager"], "home": "orders",
                                 "navigation": [ { "screen": "orders", "badge": "count('WidgetOrder', status == 'NEW')" } ] } ],
              "screens": [ { "name": "orders", "kind": "list", "concept": "WidgetOrder" } ] }
            """;

    private static final String BADGE = "count('WidgetOrder', status == 'NEW')";

    private ConceptGateway gateway;
    private RuntimeContextService context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        gateway = mock(ConceptGateway.class);
        context = mock(RuntimeContextService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExperienceEvalController(
                ExperienceCatalog.fromJson(EXPERIENCES), gateway, context, new ExperienceEvaluator())).build();
    }

    private ExecutionContext caller(String... roles) {
        ExecutionContext ctx = ExecutionContext.of("default", "someone").withRoles(Set.of(roles));
        when(context.currentContext(any())).thenReturn(ctx);
        return ctx;
    }

    private static String body(String expressions) {
        return "{\"expressions\": " + expressions + "}";
    }

    @Test
    void anExpressionFromTheCallersExperienceIsEvaluatedAsTheCaller() throws Exception {
        ExecutionContext ctx = caller("WAREHOUSEMANAGER");
        when(gateway.query(any(), any())).thenReturn(new ConceptPage(List.of(), 3, true));

        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"badge\": \"" + BADGE + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.badge").value(3));

        verify(gateway).query(any(ConceptQueryRequest.class), org.mockito.ArgumentMatchers.eq(ctx));
    }

    @Test
    void textThatIsNotInTheCallersExperienceIsNullAndNeverReachesTheGateway() throws Exception {
        caller("WAREHOUSEMANAGER");
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"a\": \"count('Secret')\", \"b\": \"1 + 1\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.a").isEmpty())
                .andExpect(jsonPath("$.values.b").isEmpty());
        verify(gateway, never()).query(any(), any());
    }

    @Test
    void aCallerWithNoExperienceGetsNullForEverythingEvenTheExactText() throws Exception {
        caller("ADMIN");
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"badge\": \"" + BADGE + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.badge").isEmpty());
        verify(gateway, never()).query(any(), any());
    }

    @Test
    void aGatewayDenialIsANullNotAnErrorAndNeverCarriesAMessage() throws Exception {
        caller("WAREHOUSEMANAGER");
        when(gateway.query(any(), any())).thenThrow(new ConceptGatewayAccessDeniedException("DENIED", "secret detail"));
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"badge\": \"" + BADGE + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.badge").isEmpty())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret detail"))));
    }

    @Test
    void moreThanTwentyFiveExpressionsIsABadRequest() throws Exception {
        caller("WAREHOUSEMANAGER");
        StringBuilder many = new StringBuilder("{");
        for (int i = 0; i <= ExperienceEvaluator.MAX_EXPRESSIONS; i++) {
            many.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": \"").append(BADGE).append('"');
        }
        many.append('}');
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON).content(body(many.toString())))
                .andExpect(status().isBadRequest());
        verify(gateway, never()).query(any(), any());
    }

    @Test
    void aMissingOrMalformedExpressionsObjectIsABadRequest() throws Exception {
        caller("WAREHOUSEMANAGER");
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON).content(body("[\"x\"]")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON).content(body("\"x\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aNonTextEntryAnswersNullForThatKeyOnly() throws Exception {
        caller("WAREHOUSEMANAGER");
        when(gateway.query(any(), any())).thenReturn(new ConceptPage(List.of(), 5, false));
        mvc.perform(post("/api/x/eval").contentType(MediaType.APPLICATION_JSON)
                        .content(body("{\"n\": 42, \"badge\": \"" + BADGE + "\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.n").isEmpty())
                .andExpect(jsonPath("$.values.badge").value(5));
    }
}
