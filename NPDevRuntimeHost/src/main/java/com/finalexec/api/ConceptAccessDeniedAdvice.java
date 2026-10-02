package com.finalexec.api;

import com.npdev.kernel.concepts.ConceptGatewayAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A concept's {@code access.write} rule refusing a save/delete is a 403, on every route that can
 * reach the Concept Gateway -- generated CRUD, flows, the agent's loopback calls. Without this,
 * {@link ConceptGatewayAccessDeniedException} escaped the generated CRUD controllers as a raw 500
 * (found live on Pigmentampas: a customer creating an order in another customer's name), while the
 * query controller already mapped the same exception to 403 by hand. Same {@code {code, message}}
 * body shape the runtime uses for {@code not_found}.
 */
@RestControllerAdvice
public class ConceptAccessDeniedAdvice {

    @ExceptionHandler(ConceptGatewayAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> accessDenied(ConceptGatewayAccessDeniedException denied) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", denied.code());
        body.put("message", denied.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}
