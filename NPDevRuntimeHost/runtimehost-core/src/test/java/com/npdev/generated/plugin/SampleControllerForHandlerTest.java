package com.npdev.generated.plugin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Map;

/**
 * B30/SEC-9: a trivial, hand-written stand-in for a mounted {@code plugin:java-controller} class,
 * used by {@code ManifestDrivenJavaControllerPluginHandlerTest} (and the fixture manifest at
 * {@code src/test/resources/npdev/plugin-runtime/plugin-controller-routes.json}) to exercise the
 * in-child dispatcher's reflective argument binding without a real generated app. No mapping
 * annotations on the methods themselves -- the dispatcher receives the matched route's method name
 * directly from the host-side proxy, it never re-derives routing from annotations.
 *
 * <p>SEC-10 (B30 lift): deliberately under {@code com.npdev.generated.plugin} -- the ONE package
 * prefix a real mounted plugin class is admitted under ({@code
 * GeneratedPluginMountPlan.PLUGIN_CONTROLLER_PACKAGE_PREFIX}) and the one {@link
 * com.finalexec.npdev.service.pluginipc.PluginRestrictedClassLoader} allows -- so this fixture is
 * loadable through the restricted loader exactly the way a real generated plugin class is, not a
 * special case carved out for tests. It previously lived under {@code
 * com.finalexec.npdev.service.pluginipc.fixtures}, which the restricted loader correctly denies
 * (that prefix is exactly the "proxy class elsewhere on the app classpath" it exists to close) --
 * moved here rather than exempted, since a test fixture pretending to be a plugin class should sit
 * where a real one actually would.
 */
public class SampleControllerForHandlerTest {

    public Map<String, Object> ping() {
        return Map.of("ok", true);
    }

    public String getUser(@PathVariable("id") String id, @RequestParam(value = "verbose", required = false) String verbose) {
        return "user-" + id + (verbose != null ? "-" + verbose : "");
    }

    public ResponseEntity<Map<String, Object>> createUser(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
