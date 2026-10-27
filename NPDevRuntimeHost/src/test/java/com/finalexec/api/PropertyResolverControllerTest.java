package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.properties.PropertyNotSettableAtScopeException;
import com.npdev.kernel.properties.PropertyResolver;
import com.npdev.kernel.properties.PropertyStoreNotComposedException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP mapping {@link PropertyResolverController} puts over {@link PropertyResolver#set}: a scope the
 * property does not allow is a 400, an app that never composed {@code workspace::PropertyValue} (so there is
 * nowhere to store a value) is a 409 that says so, and a good write answers the key and scope. The
 * resolver's own behaviour is proven in the kernel's PropertyResolver tests; same harness as
 * {@code AgentProxyControllerTest} -- a mocked {@link RuntimeContextService}, no database.
 */
class PropertyResolverControllerTest {

    private static final CompiledPropertyScope TENANT = new CompiledPropertyScope("tenant", null);
    private static final CompiledProperty PAGE_ROWS =
            new CompiledProperty("pageRows", "int", 25, List.of("tenant"), null, false);
    private static final String BODY = "{\"scopeType\":\"tenant\",\"scopeId\":\"t1\",\"value\":\"50\"}";

    private final RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
    private final PropertyResolver propertyResolver = Mockito.mock(PropertyResolver.class);

    private MockMvc mvc() {
        CompiledModel model = new CompiledModel(
                "wms.props", "1.0.0", "1.0",
                Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, List.of(),
                List.of(TENANT),
                List.of(PAGE_ROWS));
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("t1", "admin").withRoles(Set.of("ADMIN")));
        return MockMvcBuilders.standaloneSetup(
                new PropertyResolverController(runtimeContextService, propertyResolver, new ModelHolder(model))).build();
    }

    @Test
    void aGoodWriteAnswersTheKeyAndTheScope() throws Exception {
        mvc().perform(put("/api/properties/pageRows").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("pageRows"))
                .andExpect(jsonPath("$.scopeId").value("t1"));

        verify(propertyResolver).set(eq("tenant"), eq("t1"), eq("pageRows"), eq("50"), any());
    }

    @Test
    void aScopeThePropertyDoesNotAllowIsA400() throws Exception {
        doThrow(new PropertyNotSettableAtScopeException("pageRows", "tenant", List.of("user")))
                .when(propertyResolver).set(any(), any(), any(), any(), any());

        mvc().perform(put("/api/properties/pageRows").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anAppWithNoPropertyValueConceptIsA409ThatNamesTheMissingConcept() throws Exception {
        doThrow(new PropertyStoreNotComposedException("pageRows", "workspace::PropertyValue"))
                .when(propertyResolver).set(any(), any(), any(), any(), any());

        mvc().perform(put("/api/properties/pageRows").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict());
    }
}
