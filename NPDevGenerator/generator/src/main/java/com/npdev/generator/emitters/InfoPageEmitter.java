package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits {@code src/main/resources/static/info.html}, a starter page linking every URL a generated
 * app exposes (operator/business UI, flow execute endpoints, concept CRUD endpoints, monitoring
 * endpoints). Previously this page only existed for apps built through the {@code Build-NpdevApp.ps1}
 * / {@code Build-AppGenApp.ps1} ops wrapper (which calls {@code New-AppInfoPage.ps1} as a separate,
 * build-time-only step) -- any other generation path (test-corpus harnesses, ad-hoc builders) produced
 * an app with no starter page at all. Everything here is derivable from the compiled model and the
 * resolved database plan, so it is now emitted for every generated app regardless of caller.
 *
 * <p>Absolute URLs are computed client-side from {@code window.location.origin} rather than baked in,
 * since the HTTP port is a deployment choice made after generation, not a generation-time fact. Build
 * orchestration that DOES know local-machine specifics (jar path, ops toolbox, super-user key file
 * location) may still overwrite this file with a richer version -- see {@code New-AppInfoPage.ps1}.
 *
 * <p>Deliberately omits DB credentials/JDBC URL: this file is served unauthenticated (static content is
 * exempt from the API-key filter), so only the DB engine name is safe to publish here.
 */
public final class InfoPageEmitter extends AbstractEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public InfoPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model, GeneratedDatabasePlan databasePlan) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        ctx.put("dbEngine", databasePlan == null ? "in-memory" : databasePlan.engine().externalName());

        List<Map<String, String>> concepts = new ArrayList<>();
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null || concept.getName() == null) {
                continue;
            }
            String tableName = concept.getTableName();
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            Map<String, String> row = new HashMap<>();
            row.put("name", concept.getName());
            row.put("route", tableName);
            concepts.add(row);
        }
        concepts.sort(Comparator.comparing(row -> row.get("name"), String.CASE_INSENSITIVE_ORDER));

        List<String> flowNames = new ArrayList<>();
        for (CompiledFlow flow : model.getFlows()) {
            if (flow == null || flow.getName() == null || flow.getName().isBlank()) {
                continue;
            }
            flowNames.add(flow.getName());
        }
        flowNames.sort(String.CASE_INSENSITIVE_ORDER);

        ctx.put("conceptsJson", toJsonScript(concepts));
        ctx.put("flowsJson", toJsonScript(flowNames));

        writer.writeRelative("src/main/resources/static/info.html", templates.render("info-page.mustache", ctx));
    }

    /** Serialized for embedding inside a {@code <script>} block: escapes "</" so a name/route value
     *  containing a literal "&lt;/script&gt;" cannot terminate the surrounding tag early. */
    private static String toJsonScript(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value).replace("</", "<\\/");
        } catch (IOException exception) {
            throw new RuntimeException("Failed serializing info.html script data", exception);
        }
    }
}
