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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-user list UX: friendly HTTP errors (expired session), a named delete confirmation, live
 * filtering, clickable rows, accessible sort headers and a per-screen document title. Asserted
 * against the emitted app.js, like the other BusinessUiEmitter tests.
 */
public class BusinessUiEmitterListUxTest {

    private static String emitAppJs() throws Exception {
        Path modelPath = Files.createTempFile("npdev-list-ux-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "listux.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Customer",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "displayName", "type": "string", "required": true }
                      ]
                    }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-list-ux-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return CarvedUiModules.appJsWithModules(out);
    }

    @Test
    void httpFailuresMapToFriendlySentences() throws Exception {
        String js = emitAppJs();
        assertTrue(js.contains("Your session expired. Sign in again."));
        assertTrue(js.contains("You don't have permission to do that."));
        assertTrue(js.contains("That record no longer exists."));
        assertTrue(js.contains("The server had a problem. Try again."));
        assertTrue(js.contains("Can't reach the server. Check your connection."));
        assertTrue(js.contains("function sessionExpiredToast()"));
        assertTrue(js.contains("?next="), "the sign-in link must carry a return path");
    }

    @Test
    void deleteConfirmationNamesTheRecord() throws Exception {
        String js = emitAppJs();
        assertFalse(js.contains("Delete this record?"));
        assertTrue(js.contains("function recordDisplayLabel(concept, record)"));
        assertTrue(js.contains("window.confirm(\"Delete \\\"\" + label + \"\\\"?\")"));
    }

    @Test
    void filtersReloadLiveAndCanBeCleared() throws Exception {
        String js = emitAppJs();
        assertTrue(js.contains("const FILTER_DEBOUNCE_MS = 300;"));
        assertTrue(js.contains("function scheduleFilterReload(concept, delay)"));
        assertTrue(js.contains("addEventListener(\"search\""), "native clear (x) must reload");
        assertTrue(js.contains("button(\"Clear\", \"\""), "the toolbar's one Clear button resets every filter");
        assertFalse(js.contains("\"Clear filters\""), "no second clear control: the golden routines click Clear");
        assertTrue(js.contains("dataset.filterKey"), "focus must survive the re-render a reload causes");
    }

    @Test
    void rowsAreClickableAndSortHeadersAccessible() throws Exception {
        String js = emitAppJs();
        assertTrue(js.contains("tr.classList.add(\"clickable-row\")"));
        assertTrue(js.contains("closest(\"button, a, input, label, select, textarea\")"));
        assertTrue(js.contains("th.tabIndex = 0;"));
        assertTrue(js.contains("\"aria-sort\""));
        assertTrue(js.contains("event.key === \"Enter\" || event.key === \" \""));
        assertTrue(js.contains("sort-indicator"));
    }

    @Test
    void documentTitleNamesTheActiveScreen() throws Exception {
        String js = emitAppJs();
        assertTrue(js.contains("document.title = activeTitle + \" - \" + appTitle;"));
    }
}
