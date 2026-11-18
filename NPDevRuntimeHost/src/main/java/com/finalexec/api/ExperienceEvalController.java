package com.finalexec.api;

import com.finalexec.experience.ExperienceCatalog;
import com.finalexec.experience.ExperienceEvaluator;
import com.finalexec.experience.GatewayConceptReads;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * FORM P5-T4: {@code POST /api/x/eval} -- server-side values for the expressions the browser cannot compute
 * ({@code count('Ride', status == 'NEW')}, {@code sum(...)}, {@code exists(...)}).
 *
 * <p>Body {@code {"expressions": {"<key>": "<expression text>"}}}, answer {@code {"values": {"<key>": number | string |
 * boolean | null}}}. Authenticated like every {@code /api} route (the auth filters cover it, no exemption is added) and
 * capped by {@code RuntimeRequestSizeFilter}. At most {@value ExperienceEvaluator#MAX_EXPRESSIONS} expressions (more is a
 * 400). Everything else -- the allowlist (only text written in the caller's own experience is evaluated), evaluation AS the
 * caller through the concept gateway, the bounds and the null-on-any-error rule -- is {@link ExperienceEvaluator}'s; see its
 * javadoc. This is deliberately not a generic expression console.
 */
@RestController
public class ExperienceEvalController {

    private final ExperienceCatalog catalog;
    private final ConceptGateway conceptGateway;
    private final RuntimeContextService runtimeContextService;
    private final ExperienceEvaluator evaluator;

    @Autowired
    public ExperienceEvalController(ConceptGateway conceptGateway, RuntimeContextService runtimeContextService) {
        this(ExperienceCatalog.fromClasspath(), conceptGateway, runtimeContextService, new ExperienceEvaluator());
    }

    ExperienceEvalController(
            ExperienceCatalog catalog,
            ConceptGateway conceptGateway,
            RuntimeContextService runtimeContextService,
            ExperienceEvaluator evaluator
    ) {
        this.catalog = catalog;
        this.conceptGateway = conceptGateway;
        this.runtimeContextService = runtimeContextService;
        this.evaluator = evaluator;
    }

    @PostMapping("/api/x/eval")
    public Map<String, Object> eval(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        if (conceptGateway == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "concept gateway not configured");
        }
        Map<String, String> asked = parse(body);
        ExecutionContext context = runtimeContextService.currentContext(request);
        Set<String> allowed = ExperienceEvaluator.allowedExpressions(catalog.me(context.roles(), context.actorId()));
        ExperienceEvaluator.Caller caller = new ExperienceEvaluator.Caller(
                context.actorId(), context.tenantId(), context.roles(), ExecutionContext.isSystem(context));
        Map<String, Object> values = evaluator.evaluate(asked, allowed, caller, new GatewayConceptReads(conceptGateway, context));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("values", values);
        return response;
    }

    /** {@code expressions} must be an object of at most the cap; a non-text entry is asked as nothing and so answers null. */
    private static Map<String, String> parse(Map<String, Object> body) {
        Object expressions = body == null ? null : body.get("expressions");
        if (!(expressions instanceof Map<?, ?> map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "expressions must be an object of key to expression text");
        }
        if (map.size() > ExperienceEvaluator.MAX_EXPRESSIONS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at most " + ExperienceEvaluator.MAX_EXPRESSIONS + " expressions per request");
        }
        Map<String, String> asked = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            asked.put(String.valueOf(entry.getKey()), entry.getValue() instanceof String text ? text : null);
        }
        return asked;
    }
}
