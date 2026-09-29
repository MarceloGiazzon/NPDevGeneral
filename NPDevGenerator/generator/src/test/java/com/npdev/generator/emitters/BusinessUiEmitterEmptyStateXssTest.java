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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-16-resid Round 3, finding R3-F1 — XSS sinks in the generated business UI.
 *
 * <p>Every empty/loading/error placeholder used to be assembled as
 * {@code container.innerHTML = "<div class='empty'>" + message + "</div>"}. Three of those
 * concatenated {@code text(error.message)} — and {@code text()} is a null-coalescer, not an escaper,
 * despite its name — while a fourth concatenated the user's raw filter string. The server composes
 * those error messages and echoes request data into them ("Unknown field for X: …").</p>
 *
 * <p>Nothing restores a filter from the URL and the error belongs to the caller's own request, so
 * the reachable impact today is self-XSS. That is a fact about the current feature set, not about
 * the code — so the sink was removed rather than escaped, and this test pins its absence.</p>
 *
 * <p><b>Asserted against the emitted asset, not the template</b>: this bundle reproduces into every
 * generated app, so what matters is what ships.</p>
 */
public class BusinessUiEmitterEmptyStateXssTest {

    /**
     * Any assignment into innerHTML whose right-hand side is not the empty string.
     *
     * <p>{@code \s*+} is possessive on purpose: a backtracking {@code \s*} lets the engine give back
     * the space before {@code ""}, at which point the negative lookahead sees a space instead of a
     * quote and every safe clear reports as an offender.</p>
     */
    private static final Pattern NON_EMPTY_INNER_HTML =
            Pattern.compile("(\\w[\\w.$]*)\\.innerHTML\\s*+=\\s*+(?!\"\"\\s*;)(.{0,120})");

    /** Line comments describe the old sinks on purpose; only real code should be scanned. */
    private static String withoutLineComments(String source) {
        return source.replaceAll("(?m)^\\s*//.*$", "");
    }

    private static String emitAppJs() throws Exception {
        Path modelPath = Files.createTempFile("npdev-emptystate-xss-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "emptystate.xss.demo",
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
        Path out = Files.createTempDirectory("npdev-emptystate-xss-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return readAppJs(out);
    }

    private static String readAppJs(Path out) throws IOException {
        Path assets = out.resolve("src/main/resources/static/npdev-business-ui");
        try (var files = Files.walk(assets)) {
            Path appJs = files.filter(path -> path.getFileName().toString().endsWith(".js"))
                    .filter(path -> path.getFileName().toString().contains("app"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no app js emitted under " + assets));
            return Files.readString(appJs);
        }
    }

    @Test
    void noEmptyStatePlaceholderIsBuiltByStringConcatenationIntoInnerHtml() throws Exception {
        String appJs = withoutLineComments(emitAppJs());

        // W1.5: the DOM-based empty-state helper moved out of app.js entirely, into shell.js's
        // shared renderLoading/renderEmpty/renderError/renderDenied (window.NPDevShell) -- one owner
        // for every surface instead of a copy local to the generated business UI. app.js now calls
        // it rather than declaring its own local helper.
        assertTrue(appJs.contains("window.NPDevShell.renderEmpty("),
                "the generated business UI must call the shared shell empty-state helper");
        // The specific shape that carried the bug. Its absence is the regression guard: if someone
        // hand-writes another `"<div class='empty'>" + something` placeholder, this fails.
        assertEquals(-1, appJs.indexOf("\"<div class='empty'>\""),
                "no empty-state placeholder may be assembled as an HTML string any more");
    }

    /**
     * The richtext widget's sanitizer (see its own docstring in the template) parses untrusted HTML
     * into a {@code document.createElement("div")} node that is never inserted into the live
     * document, then strips it down to a small tag whitelist with every attribute removed. That
     * parse step is itself an {@code innerHTML} assignment of non-empty, non-sanitized content --
     * the one deliberate exception to the "clear only" rule below. Anchored to the exact idiom
     * (container creation immediately followed by the parse, immediately followed by the cleaning
     * walk) so a differently-shaped assignment elsewhere can't accidentally match it.
     */
    private static final Pattern SANITIZER_DETACHED_PARSE_STEP = Pattern.compile(
            "const container = document\\.createElement\\(\"div\"\\);\\s*"
                    + "container\\.innerHTML = html \\|\\| \"\";\\s*"
                    + "\\(function clean\\(node\\) \\{");

    /** Local variable names provably holding sanitizeRichText's own output: {@code name = sanitizeRichText(}. */
    private static java.util.Set<String> sanitizedRichTextVariableNames(String appJs) {
        java.util.Set<String> names = new java.util.HashSet<>();
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*sanitizeRichText\\(").matcher(appJs);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    @Test
    void everyRemainingInnerHtmlAssignmentOnlyClearsItsContainer() throws Exception {
        String appJs = withoutLineComments(emitAppJs());
        java.util.Set<String> sanitizedVariables = sanitizedRichTextVariableNames(appJs);
        boolean isSanitizerParseStep = SANITIZER_DETACHED_PARSE_STEP.matcher(appJs).find();

        // Clearing (`= ""`) cannot inject anything, and neither can a direct sanitizeRichText(...)
        // call, a variable holding its result, or the sanitizer's own detached-container parse step
        // above. Anything else assigns markup, and markup built in JS is exactly where a message the
        // server composed becomes executable. Whitelisting these known-safe forms is far more
        // durable than enumerating unsafe ones.
        Matcher matcher = NON_EMPTY_INNER_HTML.matcher(appJs);
        StringBuilder offenders = new StringBuilder();
        while (matcher.find()) {
            String target = matcher.group(1);
            String rhs = matcher.group(2).trim();
            boolean directSanitizerCall = rhs.startsWith("sanitizeRichText(");
            boolean sanitizedVariable = sanitizedVariables.contains(rhs.replaceFirst("[;,)\\s].*$", ""));
            boolean detachedParseStep = isSanitizerParseStep
                    && target.equals("container") && rhs.equals("html || \"\";");
            if (directSanitizerCall || sanitizedVariable || detachedParseStep) {
                continue;
            }
            offenders.append("\n  ").append(target).append(".innerHTML = ").append(rhs);
        }
        assertEquals("", offenders.toString(),
                "innerHTML may only clear a container, receive sanitizeRichText(...)'s own output, or be"
                        + " the sanitizer's own detached-container parse step; build anything else through the"
                        + " DOM instead. Offending assignment(s):" + offenders);
    }

    @Test
    void theFilterStringReachesTheDomAsTextNotMarkup() throws Exception {
        String appJs = emitAppJs();

        // The user's raw filter used to be concatenated into the "no matches" placeholder. It must
        // now arrive as a renderEmpty() context field (message/filtered), i.e. through textContent.
        assertTrue(appJs.contains("window.NPDevShell.renderEmpty(tableWrapper, {"),
                "the no-matches message must route the raw filter through the shared renderEmpty helper");
    }
}
