package com.npdev.generated.plugin.admintools;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R10 probe controller (NPDevSamples/probes/p7-plugin-controller): a hand-written @RestController
 * mounted from the app definition directory, surviving regeneration. Deliberately trivial -- the
 * probe is about the MOUNT MECHANISM and the D9 security wrapper, not this controller's own logic.
 * basePath here must match capabilities/adminTools/capability.plugin.json's mount.basePath exactly:
 * that value is what NPDevRuntimeHost's generated PluginControllerSecurityConfig registers its
 * role-checking interceptor against, and Spring only routes what THIS annotation declares.
 *
 * <p>{@code echo} (B30/SEC-9): exercises the isolated in-child dispatcher's reflective parameter
 * binder end to end through a REAL generated app -- a bare unit test against a hand-written fixture
 * class cannot prove the generator's own AST route extraction (PluginControllerRouteVisitor) and the
 * emitted plugin-controller-routes.json manifest actually carry a @PathVariable/@RequestParam route
 * correctly from generation through to the running child process.
 */
@RestController
@RequestMapping("/api/plugins/admin-tools")
public class AdminToolsController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("controller", "AdminToolsController");
        return body;
    }

    @GetMapping("/echo/{value}")
    public Map<String, Object> echo(
            @PathVariable("value") String value,
            @RequestParam(value = "shout", required = false) String shout
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("value", "true".equalsIgnoreCase(shout) ? value.toUpperCase() : value);
        return body;
    }
}
