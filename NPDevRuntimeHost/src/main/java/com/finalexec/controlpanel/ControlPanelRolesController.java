package com.finalexec.controlpanel;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledRole;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wave 3 (RC-B1/RC-B2, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN} Part B): the app-defined role
 * vocabulary -- name and permission ceiling ({@code grants}) -- as the model declared it
 * (RC-B1). SUPERUSER-only, like the rest of the ControlPanel. Exists so
 * {@link ControlPanelTenantUsersController}'s grant/revoke endpoints (RC-B2) never need a
 * hardcoded role list: the model owns the vocabulary, the administrator owns the binding.
 */
@RestController
@RequestMapping("/api/admin/roles")
public class ControlPanelRolesController {

    private final CompiledModel compiledModel;
    private final RuntimeContextService runtimeContextService;

    public ControlPanelRolesController(CompiledModel compiledModel, RuntimeContextService runtimeContextService) {
        this.compiledModel = compiledModel;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> listDeclaredRoles(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        List<Map<String, Object>> roles = new ArrayList<>();
        for (CompiledRole role : compiledModel.getRoles()) {
            roles.add(Map.of("name", role.name(), "grants", role.grants()));
        }
        return roles;
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
