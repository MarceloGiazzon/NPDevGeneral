package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProperty;
import com.npdev.dsl.v1.compiled.CompiledPropertyScope;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.properties.PropertyExplanation;
import com.npdev.kernel.properties.PropertyNotDeclaredException;
import com.npdev.kernel.properties.PropertyNotSettableAtScopeException;
import com.npdev.kernel.properties.PropertyResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RC-A3/RC-A5 (Move 14 Phase B items B2/B3, REG-114): the dedicated properties surface every
 * authenticated user can call regardless of role -- deliberately NOT the generic concept CRUD
 * endpoint, which stays admin-only for {@code workspace::PropertyValue} (every built-in-pack
 * concept's default). {@link PropertyResolver#resolve}/{@link PropertyResolver#explain} only ever
 * resolve the CALLER's own {@link ExecutionContext}-derived cascade, never an arbitrary row by id, so
 * opening reads to every role here cannot leak another user's or tenant's values the way widening the
 * raw CRUD grant would (see REG-114's own detail for why that path was rejected).
 *
 * <p>Writes carry their own, narrower authorization on top of {@link PropertyResolver#set}'s
 * {@code settableAt} enforcement: a caller may set a value at a scope that resolves to their OWN
 * identity (a {@code $user.id}-derived scope, and only with their own resolved id) with no extra
 * role; every other scope (tenant-wide, or any {@code $user.<tag>}-derived scope broader than "just
 * me") requires the ADMIN role, and a {@code securityRelevant} property (a real flag with teeth, per
 * its own schema doc) always requires ADMIN regardless of scope -- {@code settableAt} alone answers
 * "can this property hold a row here at all", not "may THIS caller put one there".
 */
@RestController
@RequestMapping("/api/properties")
public class PropertyResolverController {

    private final RuntimeContextService runtimeContextService;
    private final PropertyResolver propertyResolver;
    private final CompiledModel compiledModel;

    public PropertyResolverController(
            RuntimeContextService runtimeContextService, PropertyResolver propertyResolver, CompiledModel compiledModel) {
        this.runtimeContextService = runtimeContextService;
        this.propertyResolver = propertyResolver;
        this.compiledModel = compiledModel;
    }

    public record PropertyDeclaration(
            String name, String type, Object defaultValue, List<String> settableAt,
            String label, boolean securityRelevant) {
    }

    /** Every declared property -- the generated admin surface's own data source for "what to render". */
    @GetMapping
    public List<PropertyDeclaration> list(HttpServletRequest request) {
        runtimeContextService.currentContext(request); // any authenticated caller; throws if not
        return compiledModel.getProperties().stream()
                .map(p -> new PropertyDeclaration(p.name(), p.type(), p.defaultValue(), p.settableAt(), p.label(), p.securityRelevant()))
                .toList();
    }

    /** Declared scope levels, most specific first -- lets the UI render one section per scope. */
    @GetMapping("/scopes")
    public List<CompiledPropertyScope> scopes(HttpServletRequest request) {
        runtimeContextService.currentContext(request);
        return compiledModel.getPropertyScopes();
    }

    @GetMapping("/{key}")
    public PropertyExplanation explain(HttpServletRequest request, @PathVariable String key) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            return propertyResolver.explain(key, context);
        } catch (PropertyNotDeclaredException notDeclared) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notDeclared.getMessage());
        }
    }

    public record SetPropertyRequest(String scopeType, String scopeId, Object value) {
    }

    @PutMapping("/{key}")
    public Map<String, Object> set(HttpServletRequest request, @PathVariable String key, @RequestBody SetPropertyRequest body) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        CompiledProperty property = declaredProperty(key);
        authorizeWrite(property, body.scopeType(), body.scopeId(), context);
        try {
            propertyResolver.set(body.scopeType(), body.scopeId(), key, body.value(), context);
        } catch (PropertyNotSettableAtScopeException notSettable) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, notSettable.getMessage());
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("key", key);
        response.put("scopeType", body.scopeType());
        response.put("scopeId", body.scopeId());
        return response;
    }

    private CompiledProperty declaredProperty(String key) {
        for (CompiledProperty property : compiledModel.getProperties()) {
            if (property.name().equals(key)) {
                return property;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Property '" + key + "' is not declared.");
    }

    private void authorizeWrite(CompiledProperty property, String scopeType, String scopeId, ExecutionContext context) {
        if (property.securityRelevant()) {
            requireAdmin(context, "security-relevant property '" + property.name() + "'");
            return;
        }
        CompiledPropertyScope scope = compiledModel.getPropertyScopes().stream()
                .filter(s -> s.name().equals(scopeType))
                .findFirst()
                .orElse(null);
        boolean callerOwnsThisScope = scope != null && "$user.id".equals(scope.from()) && context.actorId().equals(scopeId);
        if (!callerOwnsThisScope) {
            requireAdmin(context, "scope '" + scopeType + "' (broader than the caller's own identity)");
        }
    }

    private void requireAdmin(ExecutionContext context, String reason) {
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Setting " + reason + " requires the ADMIN role.");
        }
    }
}
