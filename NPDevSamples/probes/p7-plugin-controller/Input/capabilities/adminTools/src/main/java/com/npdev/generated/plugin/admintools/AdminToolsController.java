package com.npdev.generated.plugin.admintools;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
