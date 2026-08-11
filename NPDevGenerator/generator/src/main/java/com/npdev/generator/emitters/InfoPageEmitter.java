package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits an app's OWN account of itself, twice over: {@code static/info.json} (the model) and
 * {@code static/info.html} (a renderer over it). MONITOR_PLAN A1 / D2.
 *
 * <p><b>D2-a -- the split is by WHO KNOWS THE FACT, not by who writes the file.</b> Everything here
 * is derivable from the compiled model and the resolved database plan, so it survives copying the
 * app to another machine: URLs (as PATHS, composed against the live origin at render time),
 * monitoring endpoints, flows, concepts, the database engine's NAME.
 *
 * <p>Machine-specific facts -- the jar, the {@code _ops} toolbox, the model file, the DB file, the
 * super-user key file, the port, the PID -- are <b>deliberately absent</b>. {@code npdev monitor
 * probe} supplies them at display time, and the Monitor's inspector overlays them. Baking them is
 * exactly what PORT-1 spent 2026-08-10 removing from six emitters: an app handed to someone else
 * named a drive they did not have.
 *
 * <p><b>D2-b -- the page EMBEDS its JSON; it never {@code fetch()}es it.</b> The ops wrapper also
 * writes a copy at the output root that is meant to be opened via {@code file://} while the app is
 * OFF, and {@code fetch()} from a {@code file://} origin is blocked by every browser -- so a
 * fetch-based page renders EMPTY in precisely the state a user is diagnosing. The same bytes are
 * therefore carried twice: as {@code info.json} for the CLI and the Monitor, and inline in a
 * {@code <script type="application/json">} for the page.
 *
 * <p>Also why the page must not print {@code window.location.origin} blindly: under {@code file://}
 * that string is literally {@code "null"}, so the offline page used to show {@code null/api/flows}.
 * It now detects a non-http origin and says "start the app to get live URLs" instead of showing a
 * URL that cannot work.
 *
 * <p>Deliberately omits DB credentials and the JDBC URL: this file is served unauthenticated
 * (static content is exempt from the API-key filter), so only the engine name is safe to publish.
 */
public final class InfoPageEmitter extends AbstractEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    /** Pretty-printed so `info.json` is readable by the person diagnosing, and DIFFABLE -- the
     *  determinism check compares emitted bytes across two runs and a one-line blob reports every
     *  change as "the whole file". */
    private static final ObjectWriter JSON_WRITER = OBJECT_MAPPER.writerWithDefaultPrettyPrinter();

    public static final String SCHEMA_VERSION = "npdev-app-info.v1";

    public InfoPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model, GeneratedDatabasePlan databasePlan) {
        Map<String, Object> info = buildInfo(model, databasePlan);
        String json = toJson(info);

        writer.writeRelative("src/main/resources/static/info.json", json + "\n");

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        // The SAME bytes the .json file carries. Escaping "</" is what stops a concept named
        // `</script>` from terminating the tag early.
        ctx.put("infoJson", json.replace("</", "<\\/"));
        writer.writeRelative("src/main/resources/static/info.html", templates.render("info-page.mustache", ctx));
    }

    /**
     * The record list. One row is {@code {section, property, path|value, openable, important}}.
     *
     * <p>A row carries a {@code path} (relative, composed with the live origin at render time) OR a
     * literal {@code value}. It never carries an absolute URL, because the HTTP port is a deployment
     * choice made after generation -- and never an absolute filesystem path, because that is a fact
     * about the generating machine rather than about the app.
     */
    static Map<String, Object> buildInfo(CompiledModel model, GeneratedDatabasePlan databasePlan) {
        List<Map<String, Object>> records = new ArrayList<>();

        records.add(url("URLs", "Base URL", "/"));
        records.add(url("URLs", "Home", "/"));
        records.add(url("URLs", "Login", "/login.html"));
        records.add(url("URLs", "Operator UI", "/npdev-ui"));
        records.add(url("URLs", "Business UI", "/npdev-business-ui/"));
        records.add(url("URLs", "Control Panel", "/control-panel.html"));
        records.add(url("URLs", "App tree", "/app-tree.html"));
        records.add(url("URLs", "Info page (this page)", "/info.html"));
        // The header NAME plus the development default, which the generated UI manifest already
        // publishes. Not the app's real key: this file is served unauthenticated.
        records.add(literal("URLs", "API key header", "X-Api-Key: dev-key", false));

        records.add(url("Monitoring", "Health", "/actuator/health"));
        // AMBIGUOUS row settled (helpers/info-field-partition.md): a ROUTE is generated; a COUNT
        // would be probed. Both of these are routes.
        records.add(url("Monitoring", "Audit", "/api/audit"));
        records.add(url("Monitoring", "Storage summary", "/api/admin/storage/summary"));

        records.add(url("Flows", "Flows list", "/api/flows"));
        for (String flow : flowNames(model)) {
            records.add(url("Flows", "Execute " + flow, "/api/flows/" + encodePathSegment(flow) + "/execute"));
        }

        for (Map<String, String> concept : conceptRoutes(model)) {
            records.add(url("Concepts", "CRUD " + concept.get("name"), "/api/" + concept.get("route")));
        }

        // AMBIGUOUS row settled: the old ops-wrapper page appeared to list "DB engine" twice, but the
        // two occurrences are the branches of one if/else -- exactly one is ever emitted. So: once.
        records.add(literal("Database", "DB engine",
                databasePlan == null ? "in-memory" : databasePlan.engine().externalName(), false));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("schemaVersion", SCHEMA_VERSION);
        info.put("namespace", model.getNamespace() == null ? "" : model.getNamespace());
        info.put("dbEngine", databasePlan == null ? "in-memory" : databasePlan.engine().externalName());
        info.put("flows", flowNames(model));
        info.put("concepts", conceptRoutes(model));
        info.put("records", records);
        // Stated IN the artefact rather than only in this comment: a reader holding info.json needs
        // to know that the paths it does not contain are not missing, they are probed.
        info.put("probedFacts", List.of(
                "jarPath", "opsDir", "modelPath", "dbFile", "superUserKeyFile",
                "port", "pid", "health", "dockerState"));
        info.put("probedBy", "npdev monitor probe --app-dir <this app>");
        info.put("note", "Portable facts only. Anything true of one machine rather than of this app "
                + "is supplied by `npdev monitor probe` at display time and is never written here.");
        return info;
    }

    private static Map<String, Object> url(String section, String property, String path) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", section);
        row.put("property", property);
        row.put("path", path);
        row.put("openable", true);
        row.put("important", false);
        return row;
    }

    private static Map<String, Object> literal(String section, String property, String value, boolean important) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("section", section);
        row.put("property", property);
        row.put("value", value);
        row.put("openable", false);
        row.put("important", important);
        return row;
    }

    private static List<String> flowNames(CompiledModel model) {
        List<String> names = new ArrayList<>();
        for (CompiledFlow flow : model.getFlows()) {
            if (flow == null || flow.getName() == null || flow.getName().isBlank()) {
                continue;
            }
            names.add(flow.getName());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private static List<Map<String, String>> conceptRoutes(CompiledModel model) {
        List<Map<String, String>> concepts = new ArrayList<>();
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null || concept.getName() == null) {
                continue;
            }
            String tableName = concept.getTableName();
            if (tableName == null || tableName.isBlank()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("name", concept.getName());
            row.put("route", tableName);
            concepts.add(row);
        }
        concepts.sort(Comparator.comparing(row -> row.get("name"), String.CASE_INSENSITIVE_ORDER));
        return concepts;
    }

    /** Percent-encodes what a path segment must not contain. Deliberately not URLEncoder, which is
     *  form encoding: it turns a space into '+', which is wrong inside a path. */
    private static String encodePathSegment(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int ch = b & 0xFF;
            boolean unreserved = (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '.' || ch == '_' || ch == '~';
            if (unreserved) {
                out.append((char) ch);
            } else {
                out.append('%').append(String.format("%02X", ch));
            }
        }
        return out.toString();
    }

    private static String toJson(Object value) {
        try {
            return JSON_WRITER.writeValueAsString(value);
        } catch (IOException exception) {
            throw new RuntimeException("Failed serializing info.json", exception);
        }
    }
}
