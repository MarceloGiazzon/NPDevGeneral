package com.npdev.generated.plugin.superonlytools;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * R10 probe controller (NPDevSamples/probes/p7-plugin-controller): the SUPERUSER-gated sibling of
 * AdminToolsController. Its handler body never runs in the rejection assertion -- the whole point is
 * that PluginControllerSecurityConfig's interceptor rejects the request before this method is ever
 * invoked. basePath here must match capabilities/superOnlyTools/capability.plugin.json's
 * mount.basePath exactly.
 */
@RestController
@RequestMapping("/api/plugins/super-only")
public class SuperOnlyToolsController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("controller", "SuperOnlyToolsController");
        return body;
    }
}
