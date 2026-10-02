package com.finalexec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A row-level write denial from the Concept Gateway is a 403 with the gateway's own code, never a
 * 500 -- resolved through Spring's real {@link ExceptionHandlerExceptionResolver}, the same
 * machinery that applies the advice to every generated CRUD controller in a running app.
 * (No test controller class: this package is scanned for runtime controllers, and an annotated
 * one here would be counted as an unclassified runtime surface.)
 */
class ConceptAccessDeniedAdviceTest {

    @Test
    void aRowLevelWriteDenialIsA403CarryingTheGatewayCode() throws Exception {
        StaticApplicationContext context = new StaticApplicationContext();
        context.registerSingleton("conceptAccessDeniedAdvice", ConceptAccessDeniedAdvice.class);
        context.refresh();
        ExceptionHandlerExceptionResolver resolver = new ExceptionHandlerExceptionResolver();
        resolver.getMessageConverters().add(new MappingJackson2HttpMessageConverter());
        resolver.setApplicationContext(context);
        resolver.afterPropertiesSet();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/concepts/orders");
        request.addHeader("Accept", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertNotNull(resolver.resolveException(request, response, null, new ConceptGatewayAccessDeniedException(
                "ROW_SCOPE_DENIED", "Concept Gateway denied row-level write access for concept Order")),
                "the advice must claim the exception");

        assertEquals(403, response.getStatus());
        JsonNode body = new ObjectMapper().readTree(response.getContentAsString());
        assertEquals("ROW_SCOPE_DENIED", body.path("code").asText());
        assertEquals("Concept Gateway denied row-level write access for concept Order", body.path("message").asText());
    }
}
