package com.finalexec.npdev.service.pluginipc.fixtures;

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
