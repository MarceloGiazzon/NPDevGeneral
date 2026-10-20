package com.npdev.generator.emitters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * CARVE-1: view code moved verbatim out of business-ui-app.mustache lives in static
 * {@code views/*.js} modules that app.js builds through {@code NPDevViews.module(...)}. Tests that
 * assert on "what the generated UI script ships" read app.js plus these modules.
 */
final class CarvedUiModules {

    static final List<String> FILES = List.of(
            "admin-panels.js", "declared-panels.js", "csv-io.js", "form-inputs.js", "bulk-actions.js");

    private CarvedUiModules() {
    }

    /** The emitted app.js followed by every carved module, as one string. */
    static String appJsWithModules(Path out) throws IOException {
        Path assets = out.resolve("src/main/resources/static/npdev-business-ui");
        StringBuilder all = new StringBuilder(Files.readString(assets.resolve("app.js")));
        for (String file : FILES) {
            all.append('\n').append(Files.readString(assets.resolve("views/" + file)));
        }
        return all.toString();
    }
}
