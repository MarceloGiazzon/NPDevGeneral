package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** PROC-7: every generated app ships the model-free Executions operator page and links it from the shell. */
public class BusinessUiEmitterOpsExecutionsPageTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-ops-executions-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "opsexecutions.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [ { "name": "Customer", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "displayName", "type": "string", "required": true } ] } ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-ops-executions-out-");
        new BusinessUiEmitter(TEMPLATES, new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void executionsPageAndScriptAreEmittedVerbatimUnderOps() throws Exception {
        Path ops = emit().resolve("src/main/resources/static/npdev-business-ui/ops");
        String html = Files.readString(ops.resolve("executions.html"));
        String js = Files.readString(ops.resolve("executions.js"));

        assertTrue(html.contains("<script src=\"/shell.js\"></script>"), "shell-wrapped, root-absolute shell.js");
        assertTrue(html.contains("<script src=\"./executions.js\"></script>"));
        assertTrue(js.contains("/api/executions/attention?tenant="), "lists via the attention endpoint");
        assertTrue(js.contains("NPDevShell.api.post"), "actions go through the shell's authenticated api");
        assertTrue(js.contains("window.confirm"), "cancel is confirmed first");
        assertTrue(js.contains("item.retryPath") && js.contains("item.cancelPath") && js.contains("item.unstickPath"));
    }

    @Test
    void analyticsTabIsEmittedWithItsScriptAndReadsTheAnalyticsEndpoint() throws Exception {
        Path ops = emit().resolve("src/main/resources/static/npdev-business-ui/ops");
        String html = Files.readString(ops.resolve("executions.html"));
        String js = Files.readString(ops.resolve("executions-analytics.js"));

        assertTrue(html.contains("id=\"tabAnalytics\"") && html.contains("id=\"panelAnalytics\""), "an Analytics tab");
        assertTrue(html.contains("id=\"anBottlenecks\"") && html.contains("id=\"anFlows\""), "bottleneck ranking + flow diagrams");
        assertTrue(html.contains("<script src=\"./executions-analytics.js\"></script>"));
        assertTrue(js.contains("/api/executions/analytics"), "reads the analytics endpoint");
        assertTrue(js.contains("NPDevShell.api.get"), "through the shell's authenticated api");
        assertTrue(js.contains("flow.slowestStep") && js.contains("body.bottlenecks"), "names the slowest step from the response");
        assertTrue(js.contains("tenant=") && js.contains("flow=") && js.contains("from=") && js.contains("to="));
        assertTrue(Files.readString(ops.resolve("executions.js")).contains("/api/executions/attention?tenant="),
                "the attention tab is untouched");
    }

    @Test
    void shellNpdevSectionLinksTheExecutionsPage() throws Exception {
        String shell = TEMPLATES.render("shell.js.mustache", Map.of(
                "shellVersion", "t", "uiContractCompatMin", "1", "uiContractCompatMax", "2"));
        assertTrue(shell.contains("addLeafLink(container, \"/npdev-business-ui/ops/executions.html\", \"Executions\", null)"));
    }
}
