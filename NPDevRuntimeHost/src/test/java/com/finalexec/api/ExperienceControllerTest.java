package com.finalexec.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.ModelHolder;
import com.finalexec.experience.ExperienceCatalog;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * FORM P5-T2: the HTTP layer over {@code ExperienceCatalog} -- standalone MockMvc, the kernel facade and the context
 * service mocked, so the assertions are about this controller's own wiring: who gets which experience, the shell
 * forward, and that only a flow referenced by the caller's experience (and never an internal one) is started, as the
 * caller. The decision tables themselves are in runtimehost-core's {@code ExperienceCatalogTest}.
 */
class ExperienceControllerTest {

    private static final String MODEL = """
            { "namespace": "experience.controller", "dslVersion": "3.0.0", "version": "1.0",
              "roles": [ { "name": "Agent", "concepts": { "Ticket": ["read"] } } ],
              "concepts": [ { "name": "Ticket", "fields": [
                { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "flows": [
                { "name": "CloseTicket", "expose": "ui", "steps": [ { "name": "done", "type": "return", "value": "'ok'" } ] },
                { "name": "Referenced", "expose": "api", "steps": [ { "name": "done", "type": "return", "value": "'ok'" } ] },
                { "name": "Unreferenced", "expose": "api", "steps": [ { "name": "done", "type": "return", "value": "'ok'" } ] },
                { "name": "Hidden", "expose": "internal", "steps": [ { "name": "done", "type": "return", "value": "'ok'" } ] }
              ] }
            """;

    private static final String EXPERIENCES = """
            { "experiences": [ { "name": "agent", "for": ["Agent"], "home": "tickets" } ],
              "screens": [ { "name": "tickets", "kind": "list", "dataUrl": "/api/concepts/tickets",
                "actions": [ { "label": "Close", "flow": "CloseTicket" },
                             { "label": "Ref", "flow": "Referenced" },
                             { "label": "Hid", "flow": "Hidden" } ] } ] }
            """;

    private KernelFacade kernelFacade;
    private RuntimeContextService context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        kernelFacade = Mockito.mock(KernelFacade.class);
        context = Mockito.mock(RuntimeContextService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ExperienceController(
                ExperienceCatalog.fromJson(EXPERIENCES), kernelFacade, context, new ModelHolder(model))).build();
    }

    private void caller(String... roles) {
        when(context.currentContext(any())).thenReturn(
                ExecutionContext.of("default", "someone").withRoles(Set.of(roles)));
    }

    @Test
    void meReturnsTheCallersExperienceItsScreensAndRoles() throws Exception {
        caller("AGENT");
        mvc.perform(get("/api/x/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experience.name").value("agent"))
                .andExpect(jsonPath("$.screens[0].name").value("tickets"))
                .andExpect(jsonPath("$.user.roles[0]").value("AGENT"))
                .andExpect(jsonPath("$.user.id").value("someone"));
    }

    @Test
    void meIsNullForARoleWithoutAnExperience() throws Exception {
        caller("ADMIN");
        mvc.perform(get("/api/x/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experience").doesNotExist())
                .andExpect(jsonPath("$.screens").isEmpty());
    }

    @Test
    void theShellForwardsToTheStaticClientForAKnownExperienceOnly() throws Exception {
        mvc.perform(get("/x/agent/")).andExpect(forwardedUrl("/x/index.html"));
        mvc.perform(get("/x/agent")).andExpect(forwardedUrl("/x/index.html"));
        mvc.perform(get("/x/agent/index.html")).andExpect(forwardedUrl("/x/index.html"));
        mvc.perform(get("/x/agent/app.js")).andExpect(forwardedUrl("/x/app.js"));
        mvc.perform(get("/x/nobody/")).andExpect(status().isNotFound());
        mvc.perform(get("/x/nobody/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void aFlowReferencedByTheCallersExperienceRunsAsTheCaller() throws Exception {
        caller("AGENT");
        ExecutionResult ok = ExecutionResult.ok("CloseTicket", Map.of("closed", true), List.of());
        when(kernelFacade.executeFlow(eq("CloseTicket"), anyMap(), any())).thenReturn(ok);

        mvc.perform(post("/api/x/flows/CloseTicket/execute").contentType(MediaType.APPLICATION_JSON).content("{\"id\":\"1\"}"))
                .andExpect(status().isOk());

        ExecutionContext expected = ExecutionContext.of("default", "someone").withRoles(Set.of("AGENT"));
        verify(kernelFacade).executeFlow(eq("CloseTicket"), eq(Map.of("id", "1")), eq(expected));
    }

    @Test
    void anApiFlowTheExperienceReferencesAlsoRuns() throws Exception {
        caller("AGENT");
        when(kernelFacade.executeFlow(eq("Referenced"), any(), any()))
                .thenReturn(ExecutionResult.ok("Referenced", "ok", List.of()));
        mvc.perform(post("/api/x/flows/Referenced/execute").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void anInternalFlowAnApiFlowNoScreenReferencesAndAMissingFlowAreAll404() throws Exception {
        caller("AGENT");
        for (String flow : List.of("Hidden", "Unreferenced", "NoSuchFlow")) {
            mvc.perform(post("/api/x/flows/" + flow + "/execute").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        verify(kernelFacade, never()).executeFlow(any(), any(), any());
    }

    private static final String PWA_EXPERIENCES = """
            { "experiences": [
                { "name": "agent", "title": "Agent desk", "for": ["Agent"], "home": "tickets", "device": "mobile", "themeColor": "#123456", "backgroundColor": "#ffffff" },
                { "name": "boss", "for": ["Boss"], "home": "tickets" },
                { "name": "desk", "for": ["Desk"], "home": "tickets", "device": "web" } ],
              "screens": [ { "name": "tickets", "kind": "list", "dataUrl": "/api/concepts/tickets" } ] }
            """;

    private MockMvc pwaMvc() throws Exception {
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(new ObjectMapper().readTree(MODEL)));
        return MockMvcBuilders.standaloneSetup(new ExperienceController(
                ExperienceCatalog.fromJson(PWA_EXPERIENCES), kernelFacade, context, new ModelHolder(model))).build();
    }

    @Test
    void theManifestOfAMobileExperienceIsServedAsManifestJsonAndBeatsTheClientFileRule() throws Exception {
        // the probe runner sends Accept: application/json: the preset Content-Type must still win
        pwaMvc().perform(get("/x/agent/manifest.webmanifest").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/manifest+json"))
                .andExpect(jsonPath("$.start_url").value("/x/agent/"))
                .andExpect(jsonPath("$.scope").value("/x/agent/"))
                .andExpect(jsonPath("$.display").value("standalone"))
                .andExpect(jsonPath("$.orientation").value("portrait"))
                .andExpect(jsonPath("$.name").value("Agent desk"))
                .andExpect(jsonPath("$.theme_color").value("#123456"))
                .andExpect(jsonPath("$.icons[0].src").value("/x/agent/icon-192.png"));
        // device defaults to both: installable, no forced orientation
        pwaMvc().perform(get("/x/boss/manifest.webmanifest")).andExpect(status().isOk()).andExpect(jsonPath("$.orientation").doesNotExist());
        // an ordinary client file of an experience is still forwarded
        pwaMvc().perform(get("/x/agent/app.js")).andExpect(forwardedUrl("/x/app.js"));
    }

    @Test
    void noManifestOrIconForAWebExperienceOrAnUnknownOne() throws Exception {
        for (String path : List.of("/x/desk/manifest.webmanifest", "/x/nobody/manifest.webmanifest", "/x/desk/icon-192.png",
                "/x/nobody/icon-512.png", "/x/boss/icon-512.png")) {
            pwaMvc().perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void anInstallableExperiencesIconIsServedAsPngFromTheGeneratedStaticIcons() throws Exception {
        // src/test/resources/static/x/icons/agent-192.png stands in for what ExperienceEmitter writes (icons/<name>-<size>.png)
        pwaMvc().perform(get("/x/agent/icon-192.png").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG));
        pwaMvc().perform(get("/x/agent/icon-512.png")).andExpect(status().isNotFound()); // not generated in this fixture
    }

    @Test
    void aRoleOutsideTheExperienceGets404EvenForItsUiFlow() throws Exception {
        caller("ADMIN", "USER");
        mvc.perform(post("/api/x/flows/CloseTicket/execute").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verify(kernelFacade, never()).executeFlow(any(), any(), any());
    }
}
